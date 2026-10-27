package com.findit.campus.pair.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.findit.campus.common.api.ErrorCode;
import com.findit.campus.common.error.BizException;
import com.findit.campus.pair.config.PairPolicyProperties;
import com.findit.campus.pair.config.PairScoreProperties;
import com.findit.campus.pair.engine.PairScoringMetrics;
import com.findit.campus.pair.web.dto.PairScanResult;
import com.findit.campus.record.mapper.RecordMapper;

/**
 * 每日兜底扫描 + 手动触发（架构 §5.4）。<b>不新增调度器</b>：到期统计也挂在这一个任务里。
 *
 * <p>它补的是三类漏算：
 * <ol>
 *   <li><b>从未算过</b>：线程池队列满时任务被丢弃、进程在打分前重启 —— {@code pair_state=PENDING}</li>
 *   <li><b>算到一半挂了</b>：{@code pair_state=SCORING} 且 {@code pair_scored_at} 已过期</li>
 *   <li><b>降级了</b>：{@code pair_state=DEGRADED}，key 恢复后由逐对的重算逻辑翻成 DEEPSEEK</li>
 * </ol>
 *
 * <p><b>并发控制</b>：进程内 {@link AtomicBoolean} 单飞 —— 两个扫描同时跑会把 LLM 调用量翻倍，
 * 且它们的锁竞争只会互相跳过。多实例部署时这个单飞不成立，需要用数据库锁
 * （本项目是单实例演示形态，写进已知限制，不做假的多实例保证）。
 *
 * <p><b>单次限量</b>：{@code scan-limit} 限制记录数、{@code max-llm-calls} 限制 LLM 调用数。
 * 达到上限就停并把 {@code limited=true} 返回，下一轮继续 —— 宁可分多次跑完，
 * 也不要在一次扫描里把线程和配额耗尽。
 *
 * <p><b>禁止清单</b>（架构 §5.4）：不写 {@code t_record.status/completed_at/deleted_at/updated_at}、
 * 不写 {@code t_pair_score.status/confirmed_at}、不关闭任何记录。本类连这些 SQL 都不引用。
 */
@Service
public class PairScanService {

    private static final Logger log = LoggerFactory.getLogger(PairScanService.class);

    private final RecordMapper recordMapper;
    private final PairScoringService scoringService;
    private final PairScoreProperties scoreProperties;
    private final PairPolicyProperties policy;
    private final PairScoringMetrics metrics;

    private final AtomicBoolean running = new AtomicBoolean(false);

    public PairScanService(RecordMapper recordMapper,
                           PairScoringService scoringService,
                           PairScoreProperties scoreProperties,
                           PairPolicyProperties policy,
                           PairScoringMetrics metrics) {
        this.recordMapper = recordMapper;
        this.scoringService = scoringService;
        this.scoreProperties = scoreProperties;
        this.policy = policy;
        this.metrics = metrics;
    }

    /** 每日 03:30（默认）跑一次；失败只记日志，不抛（定时任务抛异常只会污染日志）。 */
    @Scheduled(cron = "${findit.pair.score.scan-cron:0 30 3 * * *}", zone = "Asia/Shanghai")
    public void scheduledScan() {
        try {
            PairScanResult result = scan("scheduled");
            log.info("pair scan scheduled done scanned={} rescored={} degraded={} skipped={} expired={} limited={} elapsedMs={}",
                    result.scanned(), result.rescored(), result.degraded(), result.skipped(),
                    result.expired(), result.limited(), result.elapsedMs());
        } catch (RuntimeException e) {
            log.warn("pair scan scheduled failed cause={}", e.getMessage());
        }
    }

    /**
     * 跑一轮扫描。
     *
     * @param trigger {@code scheduled} / {@code http} / {@code startup}
     * @throws BizException 409 {@code 50006}：已有一轮在跑（契约 §4.18）
     */
    public PairScanResult scan(String trigger) {
        if (!running.compareAndSet(false, true)) {
            throw BizException.of(ErrorCode.PAIR_SCAN_IN_PROGRESS);
        }
        long startedAt = System.nanoTime();
        long llmCallsAtStart = metrics.llmCalls();
        try {
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime staleBefore = now.minusMinutes(scoreProperties.getStaleMinutes());
            List<Long> targets = recordMapper.selectPairScanTargets(staleBefore, scoreProperties.getScanLimit());

            int scanned = 0;
            int rescored = 0;
            int degraded = 0;
            int skipped = 0;
            boolean limited = false;

            for (Long recordId : targets) {
                if (metrics.llmCalls() - llmCallsAtStart >= scoreProperties.getMaxLlmCalls()) {
                    limited = true;
                    log.warn("pair scan 达到 maxLlmCalls={} 上限，提前停止（还有 {} 条待扫）",
                            scoreProperties.getMaxLlmCalls(), targets.size() - scanned);
                    break;
                }
                PairScoringService.RecordScoreOutcome outcome = scoringService.scoreRecord(recordId, trigger);
                if (outcome.failed()) {
                    continue; // 单条失败不占 scanned（它会在下一轮重新成为候选）
                }
                scanned++;
                rescored += outcome.written();
                skipped += outcome.skipped() + outcome.locked();
                if (PairScoringService.STATE_DEGRADED.equals(outcome.pairState())) {
                    degraded++;
                }
            }

            // 到期统计：只计数，不改写（可见性由读时谓词保证，漏跑一轮也不会泄露）
            long expired = recordMapper.countExpiredCompleted(policy.completedCutoff(now));
            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;
            log.info("pair scan done trigger={} scanned={} rescored={} degraded={} skipped={} expired={} limited={} elapsedMs={} counters={}",
                    trigger, scanned, rescored, degraded, skipped, expired, limited, elapsedMs,
                    metrics.snapshot());
            return PairScanResult.of(scanned, rescored, degraded, skipped, expired, limited, elapsedMs,
                    trigger, metrics.snapshot());
        } finally {
            running.set(false);
        }
    }
}
