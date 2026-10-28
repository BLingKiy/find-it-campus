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
import com.findit.campus.pair.engine.LlmCircuitBreaker;
import com.findit.campus.pair.engine.PairScoringMetrics;
import com.findit.campus.pair.web.dto.PairScanResult;
import com.findit.campus.record.mapper.RecordMapper;

/**
 * 兜底扫描 + 降级重扫 + 手动触发（架构 §5.4 + ZHYYKFX-88）。
 *
 * <p>两条调度、一个执行体：
 * <ul>
 *   <li><b>每日全量</b>（{@code scan-cron}，默认 03:30）：补"从未算过"与"算到一半挂了"，
 *       顺带覆盖冷却已过的降级记录。它就是 v2.0 的原扫描，行为不变。</li>
 *   <li><b>降级重扫</b>（{@code degraded-scan-cron}，默认每 10 分钟）：<b>只</b>扫
 *       {@code pair_state=DEGRADED} 且距上次打分 ≥ {@code degraded-retry-minutes} 的记录。
 *       它把"LLM 恢复后最多等 24 小时"压到"下一个 10 分钟周期"，这是本单的核心收益。</li>
 * </ul>
 *
 * <p><b>并发控制</b>：两条调度共用进程内 {@link AtomicBoolean} 单飞 —— 两个扫描同时跑会把
 * LLM 调用量翻倍，且它们的锁竞争只会互相跳过。冲突时 HTTP 入口拿 409/50006（契约不变），
 * 定时入口记一行 skip 日志后正常返回。多实例部署时这个单飞不成立，需要数据库锁
 * （本项目是单实例演示形态，写进已知限制，不做假的多实例保证）。
 *
 * <p><b>额度约束</b>：{@code max-llm-calls} 是进程内本轮 LLM 调用差值的硬上限；
 * 全量还有 {@code scan-limit}、降级还有更小的 {@code degraded-scan-limit} 限制单轮记录数。
 * 达到上限就停并把 {@code limited=true} 返回，下一轮继续 —— 宁可分多次跑完，
 * 也不要在一次扫描里把线程和配额耗尽。
 *
 * <p><b>降级重扫的熔断前置</b>：熔断打开时不碰任何记录。否则每一对都会"抓一次本地降级分、
 * 更新 updated_at"，把冷却时钟往后推，等于让已能恢复的记录多等一个 60 分钟窗口。
 * 这里只用只读的 {@link LlmCircuitBreaker#isOpen()}，不会占用 HALF_OPEN 的探测名额。
 *
 * <p><b>禁止清单</b>（架构 §5.4）：不写 {@code t_record.status/completed_at/deleted_at/updated_at}、
 * 不写 {@code t_pair_score.status/confirmed_at}、不关闭任何记录。本类连这些 SQL 都不引用。
 */
@Service
public class PairScanService {

    private static final Logger log = LoggerFactory.getLogger(PairScanService.class);

    /** 扫描口径：决定候选 SQL、单轮上限，以及是否做到期统计。 */
    public enum Scope {
        FULL,
        DEGRADED
    }

    /** 本轮整体跳过的原因（进响应体，便于区分"没活干"与"熔断挡着"）。 */
    public static final String SKIP_CIRCUIT_OPEN = "CIRCUIT_OPEN";

    private final RecordMapper recordMapper;
    private final PairScoringService scoringService;
    private final PairScoreProperties scoreProperties;
    private final PairPolicyProperties policy;
    private final PairScoringMetrics metrics;
    private final LlmCircuitBreaker circuitBreaker;

    private final AtomicBoolean running = new AtomicBoolean(false);

    public PairScanService(RecordMapper recordMapper,
                           PairScoringService scoringService,
                           PairScoreProperties scoreProperties,
                           PairPolicyProperties policy,
                           PairScoringMetrics metrics,
                           LlmCircuitBreaker circuitBreaker) {
        this.recordMapper = recordMapper;
        this.scoringService = scoringService;
        this.scoreProperties = scoreProperties;
        this.policy = policy;
        this.metrics = metrics;
        this.circuitBreaker = circuitBreaker;
    }

    /** 每日 03:30（默认）跑一次；失败只记日志，不抛（定时任务抛异常只会污染日志）。 */
    @Scheduled(cron = "${findit.pair.score.scan-cron:0 30 3 * * *}", zone = "Asia/Shanghai")
    public void scheduledScan() {
        try {
            PairScanResult result = scan("scheduled");
            logScanDone(result);
        } catch (RuntimeException e) {
            log.warn("pair scan scheduled failed cause={}", e.getMessage());
        }
    }

    /**
     * 每 10 分钟（默认）只扫降级记录。
     *
     * <p>刷新周期由 {@code degraded-scan-cron} 控制；{@code degraded-scan-enabled=false} 时
     * 退回 v2.0 的"只有每日全量"行为。失败不影响主流程，只记日志。
     */
    @Scheduled(cron = "${findit.pair.score.degraded-scan-cron:0 */10 * * * *}", zone = "Asia/Shanghai")
    public void scheduledDegradedScan() {
        if (!scoreProperties.isDegradedScanEnabled()) {
            return;
        }
        try {
            PairScanResult result = scanDegraded("degraded-scheduled");
            logScanDone(result);
        } catch (BizException e) {
            // 与全量扫描撞车：不是错误，下一轮/全量扫描会覆盖，记一行 skip 即可
            log.info("pair degraded scan skipped reason=IN_PROGRESS code={}", e.errorCode().code());
        } catch (RuntimeException e) {
            log.warn("pair degraded scan scheduled failed cause={}", e.getMessage());
        }
    }

    /**
     * 跑一轮<b>全量</b>扫描（v2.0 行为，候选口径只多了降级冷却）。
     *
     * @param trigger {@code scheduled} / {@code http} / {@code startup}
     * @throws BizException 409 {@code 50006}：已有一轮在跑（契约 §4.18）
     */
    public PairScanResult scan(String trigger) {
        return run(Scope.FULL, trigger);
    }

    /**
     * 跑一轮<b>只扫降级记录</b>的扫描（ZHYYKFX-88）。
     *
     * <p>熔断打开时整体跳过并返回 {@code skipReason=CIRCUIT_OPEN}：不空转、不推后冷却时钟。
     *
     * @param trigger {@code degraded-scheduled} / {@code http-degraded} / {@code llm-recovered}
     * @throws BizException 409 {@code 50006}：已有一轮在跑
     */
    public PairScanResult scanDegraded(String trigger) {
        return run(Scope.DEGRADED, trigger);
    }

    private PairScanResult run(Scope scope, String trigger) {
        if (!running.compareAndSet(false, true)) {
            throw BizException.of(ErrorCode.PAIR_SCAN_IN_PROGRESS);
        }
        long startedAt = System.nanoTime();
        long llmCallsAtStart = metrics.llmCalls();
        try {
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime staleBefore = now.minusMinutes(scoreProperties.getStaleMinutes());
            LocalDateTime degradedBefore = now.minusMinutes(scoreProperties.getDegradedRetryMinutes());
            LocalDateTime completedCutoff = policy.completedCutoff(now);

            if (scope == Scope.DEGRADED && circuitBreaker.isOpen()) {
                long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;
                log.info("pair degraded scan skipped trigger={} reason={} remainingSeconds={} elapsedMs={}",
                        trigger, SKIP_CIRCUIT_OPEN, circuitBreaker.openSecondsRemaining(), elapsedMs);
                return PairScanResult.skipped(trigger, scope.name(), SKIP_CIRCUIT_OPEN, elapsedMs,
                        metrics.snapshot());
            }

            // 扫描口径与召回一致：过期 COMPLETED 不再成为目标（P2-4），即便它停在 DEGRADED。
            List<Long> targets = scope == Scope.DEGRADED
                    ? recordMapper.selectDegradedScanTargets(
                            degradedBefore, completedCutoff, scoreProperties.getDegradedScanLimit())
                    : recordMapper.selectPairScanTargets(
                            staleBefore, completedCutoff, degradedBefore, scoreProperties.getScanLimit());

            int scanned = 0;
            int rescored = 0;
            int degraded = 0;
            int skipped = 0;
            boolean limited = false;

            // 预算闸门传进 scoreRecord：否则一条记录的上百个候选 pair 会把 max-llm-calls 撑破
            PairScoringService.LlmCallBudget budget = () ->
                    metrics.llmCalls() - llmCallsAtStart >= scoreProperties.getMaxLlmCalls();

            for (Long recordId : targets) {
                if (budget.exhausted()) {
                    limited = true;
                    log.warn("pair scan 达到 maxLlmCalls={} 上限，提前停止 scope={} trigger={}（还有 {} 条待扫）",
                            scoreProperties.getMaxLlmCalls(), scope, trigger, targets.size() - scanned);
                    break;
                }
                PairScoringService.RecordScoreOutcome outcome = scoringService.scoreRecord(recordId, trigger, budget);
                if (outcome.failed()) {
                    continue; // 单条失败不占 scanned（它会在下一轮重新成为候选）
                }
                scanned++;
                rescored += outcome.written();
                skipped += outcome.skipped() + outcome.locked();
                if (PairScoringService.STATE_DEGRADED.equals(outcome.pairState())) {
                    degraded++;
                }
                if (outcome.truncated()) {
                    // 这一条记录还有 pair 没算：它是下一轮的第一个候选（pair_scored_at 未被推进）
                    limited = true;
                    log.warn("pair scan 记录被预算截断 scope={} trigger={} recordId={}（剩余 pair 留到下一轮）",
                            scope, trigger, recordId);
                    break;
                }
            }

            // 到期统计：只计数，不改写（可见性由读时谓词保证，漏跑一轮也不会泄露）。
            // 降级重扫每 10 分钟一次，不做这个随表增长的 COUNT：-1 = 本轮未统计。
            long expired = scope == Scope.FULL ? recordMapper.countExpiredCompleted(completedCutoff) : -1L;
            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;
            log.info("pair scan done scope={} trigger={} scanned={} rescored={} degraded={} skipped={} expired={} limited={} elapsedMs={} counters={}",
                    scope, trigger, scanned, rescored, degraded, skipped, expired, limited, elapsedMs,
                    metrics.snapshot());
            return PairScanResult.of(scanned, rescored, degraded, skipped, expired, limited, elapsedMs,
                    trigger, scope.name(), null, metrics.snapshot());
        } finally {
            running.set(false);
        }
    }

    /** 两条调度共用的一条汇总日志：字段一致，排障时可以直接对比。 */
    private static void logScanDone(PairScanResult result) {
        log.info("pair scan summary scope={} trigger={} scanned={} rescored={} degraded={} skipped={} expired={} limited={} skipReason={} elapsedMs={}",
                result.scope(), result.trigger(), result.scanned(), result.rescored(), result.degraded(),
                result.skipped(), result.expired(), result.limited(), result.skipReason(), result.elapsedMs());
    }
}
