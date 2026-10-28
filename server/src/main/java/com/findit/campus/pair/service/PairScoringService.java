package com.findit.campus.pair.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.findit.campus.common.masking.MaskingPolicy;
import com.findit.campus.notification.domain.Notification;
import com.findit.campus.notification.mapper.NotificationMapper;
import com.findit.campus.pair.config.PairPolicyProperties;
import com.findit.campus.pair.config.PairScoreProperties;
import com.findit.campus.pair.domain.PairScore;
import com.findit.campus.pair.domain.PairSide;
import com.findit.campus.pair.engine.PairContentHasher;
import com.findit.campus.pair.engine.PairEngines;
import com.findit.campus.pair.engine.PairPrompt;
import com.findit.campus.pair.engine.PairScoreCommand;
import com.findit.campus.pair.engine.PairScoreResult;
import com.findit.campus.pair.engine.PairScoringMetrics;
import com.findit.campus.pair.engine.deepseek.DeepSeekPairScorer;
import com.findit.campus.pair.engine.local.LocalKeywordPairScorer;
import com.findit.campus.pair.mapper.PairScoreMapper;
import com.findit.campus.record.domain.Record;
import com.findit.campus.record.domain.RecordStatus;
import com.findit.campus.record.domain.RecordType;
import com.findit.campus.record.mapper.RecordMapper;
import com.findit.campus.record.service.RecordVisibilityPolicy;

/**
 * 写入 {@code t_pair_score} 的<b>唯一</b>位置（发布 / 编辑 / 兜底扫描 / 手动重试共用）。
 *
 * <p>一次 {@link #scoreRecord(long, String)} 做四件事：召回 → 逐对打分 → upsert → 跨阈值通知，
 * 最后把 {@code t_record.pair_state} 落到 READY/DEGRADED。它<b>不写</b>
 * {@code t_record.status/completed_at/updated_at}，也不写 {@code t_pair_score.status/confirmed_at}
 * —— 这两组列分别属于「本人动作」和「E1 的 pair 动作」，本类连写它们的 SQL 都没有。
 *
 * <p><b>幂等三件套</b>：
 * <ol>
 *   <li>唯一键 {@code (lost_id, found_id)} + 只更新分数类列的 upsert；</li>
 *   <li>内容哈希未变 → 直接跳过，<b>不调 LLM、不更新 updated_at</b>；</li>
 *   <li>MySQL 命名锁，同一对跨入口互斥。</li>
 * </ol>
 *
 * <p>唯一的例外是<b>降级重算</b>：已落库的行若 {@code engine=LOCAL_KEYWORD} 且冷却窗口已过，
 * 允许在哈希未变的情况下重算一次。这是任务书「降级行在 key 恢复后可被重算为 LLM」的落点，
 * 与「三个入口不重复调 LLM」并不冲突 —— 后者说的是已成功的 pair 不被重复计费。
 */
@Service
public class PairScoringService {

    private static final Logger log = LoggerFactory.getLogger(PairScoringService.class);

    /** {@code t_record.pair_state} 的取值（DDL CHECK 同值域）。 */
    public static final String STATE_READY = "READY";
    public static final String STATE_DEGRADED = "DEGRADED";

    private final RecordMapper recordMapper;
    private final PairScoreMapper pairScoreMapper;
    private final PairRecallService recallService;
    private final PairLockService lockService;
    private final NotificationMapper notificationMapper;
    private final DeepSeekPairScorer llmScorer;
    private final LocalKeywordPairScorer localScorer;
    private final PairPolicyProperties policy;
    private final PairScoreProperties scoreProperties;
    private final PairScoringMetrics metrics;
    private final TransactionTemplate transactionTemplate;

    public PairScoringService(RecordMapper recordMapper,
                              PairScoreMapper pairScoreMapper,
                              PairRecallService recallService,
                              PairLockService lockService,
                              NotificationMapper notificationMapper,
                              DeepSeekPairScorer llmScorer,
                              LocalKeywordPairScorer localScorer,
                              PairPolicyProperties policy,
                              PairScoreProperties scoreProperties,
                              PairScoringMetrics metrics,
                              PlatformTransactionManager transactionManager) {
        this.recordMapper = recordMapper;
        this.pairScoreMapper = pairScoreMapper;
        this.recallService = recallService;
        this.lockService = lockService;
        this.notificationMapper = notificationMapper;
        this.llmScorer = llmScorer;
        this.localScorer = localScorer;
        this.policy = policy;
        this.scoreProperties = scoreProperties;
        this.metrics = metrics;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * 给一条记录补齐它的所有候选 pair。
     *
     * @param trigger {@code publish} / {@code scan} / {@code manual}，只进日志（不落库：落库会引入一个
     *                与业务无关的列，排障看日志足够）
     * @return 本轮统计，供扫描汇总；单条记录失败不成异常（内部已兜住），除非数据库不可用
     */
    public RecordScoreOutcome scoreRecord(long recordId, String trigger) {
        Record record = recordMapper.selectById(recordId);
        if (record == null) {
            return RecordScoreOutcome.skipped(recordId, "NOT_FOUND");
        }
        LocalDateTime now = LocalDateTime.now();
        // 召回口径的单点判据（契约 §4.8 / P2-4）：CLOSED 恒不可；COMPLETED 仅保留期内可。
        // 扫描 SQL 已经排除过期 COMPLETED，这里再兜一层是因为 publish/update/manual 也走本方法，
        // 而且记录可能在本轮扫描开始后才过期。过期记录不能再产生 pair/通知（详情按规则 404）。
        if (!RecordVisibilityPolicy.isMatchable(record.getStatus(), record.getCompletedAt(),
                policy.getCompletedRetentionDays(), now)) {
            return RecordScoreOutcome.skipped(recordId,
                    record.getStatus() == RecordStatus.CLOSED ? "CLOSED" : "EXPIRED");
        }
        PairSide source = PairRecallService.toSide(record);
        recordMapper.markPairScoring(recordId);
        try {
            List<PairSide> candidates = withExistingCounterparts(recordId, recallService.recall(source));
            int written = 0;
            int skipped = 0;
            int locked = 0;
            for (PairSide candidate : candidates) {
                PairSide lost = source.type() == RecordType.LOST ? source : candidate;
                PairSide found = source.type() == RecordType.LOST ? candidate : source;
                PairOutcome outcome = scorePair(lost, found, trigger);
                switch (outcome.status()) {
                    case WRITTEN -> written++;
                    case SKIPPED_UNCHANGED -> skipped++;
                    case SKIPPED_LOCKED -> locked++;
                    case FAILED -> {
                        // scorePair 内部不抛业务异常；FAILED 只在扩展时使用
                    }
                }
            }
            int degradedPairs = pairScoreMapper.countDegradedByRecord(recordId);
            String state = degradedPairs > 0 ? STATE_DEGRADED : STATE_READY;
            recordMapper.markPairScored(recordId, state, LocalDateTime.now());
            log.info("pair record scored recordId={} trigger={} candidates={} written={} skipped={} locked={} state={} degradedPairs={}",
                    recordId, trigger, candidates.size(), written, skipped, locked, state, degradedPairs);
            return new RecordScoreOutcome(recordId, state, candidates.size(), written, skipped, locked,
                    degradedPairs, null);
        } catch (RuntimeException e) {
            // 保持 PENDING/SCORING，扫描会重试；不要让一条记录的失败中断整批扫描
            log.warn("pair record scoring failed recordId={} trigger={} cause={}", recordId, trigger, e.getMessage());
            return RecordScoreOutcome.failed(recordId, e.getMessage());
        }
    }

    /**
     * 召回候选 ∪ 已存在 pair 的对端。
     *
     * <p><b>为什么必须并上"已存在 pair 的对端"</b>：召回只取 topN，随着时间推移，同一条记录先前的
     * 某个对端可能落到 topN 之外。如果只重算召回结果，那条老 pair 的 hash 永远停在旧内容上，
     * 分数也永远不再更新 —— 用户改了标题，界面上却还有一条按旧标题算出来的 0.9。
     * 这是"改了内容却不重算"的另一种形态，单靠 hash 对比发现不了（因为根本不会走到对比那一步）。
     *
     * <p>代价是每条记录每次打分要额外读一次自己的 pair 列表与对端记录（≤ 几十行）。
     * 用 {@code pre-score-pool-size} 作为上限，最老的排前面，保证被反复编辑的记录不会饿死老 pair。
     */
    private List<PairSide> withExistingCounterparts(long recordId, List<PairSide> recalled) {
        List<PairScore> existing = pairScoreMapper.selectByRecord(recordId, scoreProperties.getPreScorePoolSize());
        if (existing.isEmpty()) {
            return recalled;
        }
        Set<Long> known = new LinkedHashSet<>();
        for (PairSide side : recalled) {
            known.add(side.recordId());
        }
        List<Long> counterpartIds = new ArrayList<>();
        for (PairScore pair : existing) {
            long counterpart = pair.getLostId() == recordId ? pair.getFoundId() : pair.getLostId();
            if (known.add(counterpart)) {
                counterpartIds.add(counterpart);
            }
        }
        if (counterpartIds.isEmpty()) {
            return recalled;
        }
        List<PairSide> merged = new ArrayList<>(recalled);
        for (Record counterpart : recordMapper.selectByIds(counterpartIds)) {
            if (isMatchable(counterpart)) {
                merged.add(PairRecallService.toSide(counterpart));
            }
        }
        return merged;
    }

    /** 与召回同口径：未删除（SQL 已滤）、非 CLOSED、COMPLETED 需在保留期内。 */
    private boolean isMatchable(Record record) {
        if (record.getStatus() == RecordStatus.CLOSED) {
            return false;
        }
        if (record.getStatus() == RecordStatus.COMPLETED) {
            return record.getCompletedAt() != null
                    && record.getCompletedAt().isAfter(policy.completedCutoff(LocalDateTime.now()));
        }
        return true;
    }

    /**
     * 一对一的打分与落库。方向由调用方保证：{@code lost.type == LOST}、{@code found.type == FOUND}。
     *
     * <p>锁的持有期覆盖 LLM 调用（见 {@link PairLockService} 的代价说明）；拿不到锁就跳过，
     * 由扫描补，不在这里等待。
     *
     * <p><b>快路径</b>：先做一次不加锁的读取，哈希未变且无需降级重算就直接返回。
     * 这让"每日扫描把整池都过一遍"从「每对 3 次往返（GET_LOCK + SELECT + RELEASE_LOCK）」
     * 降到「每对 1 次 SELECT」。正确性不受影响：真正要写的时候仍然会重新加锁并在锁内<b>再读一次</b>，
     * 两次读之间被别人改过也只会导致"这次不写"，不会写坏。
     */
    public PairOutcome scorePair(PairSide lost, PairSide found, String trigger) {
        String lostHash = PairContentHasher.hash(lost);
        String foundHash = PairContentHasher.hash(found);

        PairScore snapshot = pairScoreMapper.selectByPair(lost.recordId(), found.recordId());
        if (unchanged(snapshot, lostHash, foundHash) && !shouldRetryDegraded(snapshot, LocalDateTime.now())) {
            metrics.skippedUnchanged();
            // 分数没变，但通知可能上次写失败了（DB 抖动）。通知是幂等的，补一次成本极低。
            if (snapshot.getScore().compareTo(
                    policy.forEngine(snapshot.getEngine()).notifyThreshold()) >= 0) {
                ensureNotifications(lost, found, snapshot.getId(), snapshot.getScore());
            }
            log.info("pair score skipped reason=UNCHANGED fastPath=true lostId={} foundId={} trigger={} pairId={}",
                    lost.recordId(), found.recordId(), trigger, snapshot.getId());
            return PairOutcome.skipped(PairOutcome.Status.SKIPPED_UNCHANGED);
        }

        try (PairLock lock = lockService.tryAcquire(lost.recordId(), found.recordId())) {
            if (lock == null) {
                metrics.lockMiss();
                log.info("pair score skipped reason=LOCKED lostId={} foundId={} trigger={}",
                        lost.recordId(), found.recordId(), trigger);
                return PairOutcome.skipped(PairOutcome.Status.SKIPPED_LOCKED);
            }
            PairScore existing = pairScoreMapper.selectByPair(lost.recordId(), found.recordId());
            LocalDateTime now = LocalDateTime.now();
            if (unchanged(existing, lostHash, foundHash) && !shouldRetryDegraded(existing, now)) {
                metrics.skippedUnchanged();
                if (existing.getScore().compareTo(
                        policy.forEngine(existing.getEngine()).notifyThreshold()) >= 0) {
                    ensureNotifications(lost, found, existing.getId(), existing.getScore());
                }
                log.info("pair score skipped reason=UNCHANGED fastPath=false lostId={} foundId={} trigger={} pairId={}",
                        lost.recordId(), found.recordId(), trigger, existing.getId());
                return PairOutcome.skipped(PairOutcome.Status.SKIPPED_UNCHANGED);
            }

            PairScoreCommand command = new PairScoreCommand(lost, found, PairPrompt.VERSION);
            PairScoreResult llm = llmScorer.score(command);
            PairScoreResult effective = llm.hasScore()
                    ? llm
                    : withLocalScore(localScorer.score(command), llm);

            BigDecimal score = effective.score();
            Integer latencyMs = effective.latencyMs();
            PairScore row = new PairScore();
            row.setLostId(lost.recordId());
            row.setFoundId(found.recordId());
            row.setScore(score);
            row.setEngine(effective.engine());
            row.setDegradedReason(effective.degradedReason());
            row.setPromptVersion(PairPrompt.VERSION);
            row.setLatencyMs(latencyMs);
            row.setPromptTokens(effective.promptTokens());
            row.setCompletionTokens(effective.completionTokens());
            row.setLostHash(lostHash);
            row.setFoundHash(foundHash);

            transactionTemplate.executeWithoutResult(status -> {
                pairScoreMapper.upsertScore(row);
                PairScore persisted = pairScoreMapper.selectByPair(lost.recordId(), found.recordId());
                if (persisted != null && score.compareTo(
                        policy.forEngine(effective.engine()).notifyThreshold()) >= 0) {
                    publishNotifications(lost, found, persisted.getId(), score);
                }
            });
            metrics.pairWritten();
            PairScore persisted = pairScoreMapper.selectByPair(lost.recordId(), found.recordId());
            log.info("pair score written lostId={} foundId={} trigger={} pairId={} engine={} score={} degradedReason={} latencyMs={} promptTokens={} completionTokens={}",
                    lost.recordId(), found.recordId(), trigger,
                    persisted == null ? null : persisted.getId(), row.getEngine(), score,
                    row.getDegradedReason(), latencyMs, row.getPromptTokens(), row.getCompletionTokens());
            return new PairOutcome(PairOutcome.Status.WRITTEN, persisted == null ? null : persisted.getId(),
                    score, row.getEngine(), row.getDegradedReason());
        }
    }

    /** 哈希未变（存在且两端 hash 都相等）。 */
    private static boolean unchanged(PairScore existing, String lostHash, String foundHash) {
        return existing != null
                && lostHash.equals(existing.getLostHash())
                && foundHash.equals(existing.getFoundHash());
    }

    /**
     * 降级重算判定：只有<b>已落库且是本地分</b>的行才允许在哈希未变时重算，且必须过冷却窗口。
     *
     * <p>为什么冷却：key 刚恢复时，一次扫描可能面对上千条降级行；无冷却会把它们在同一分钟里
     * 全部重算，形成一次自制的流量尖峰（也可能触发供应商限流）。
     */
    private boolean shouldRetryDegraded(PairScore existing, LocalDateTime now) {
        if (existing == null || !PairEngines.LOCAL_KEYWORD.equals(existing.getEngine())) {
            return false;
        }
        LocalDateTime updatedAt = existing.getUpdatedAt();
        if (updatedAt == null) {
            return true;
        }
        return updatedAt.isBefore(now.minusMinutes(scoreProperties.getDegradedRetryMinutes()));
    }

    /**
     * 降级合并：分数用本地算法，原因与耗时用"失败那次"的。
     *
     * <p>耗时保留失败调用的实测值（>0 时），因为排障要看的是"卡了多久才降级"；
     * EMPTY_KEY 没有发起调用，耗时写 {@code null} —— DDL 注释里"本地引擎为 NULL"的口径就是它。
     */
    private static PairScoreResult withLocalScore(PairScoreResult local, PairScoreResult degradedLlm) {
        Integer latency = degradedLlm.latencyMs() != null && degradedLlm.latencyMs() > 0
                ? degradedLlm.latencyMs()
                : null;
        return new PairScoreResult(local.score(), PairEngines.LOCAL_KEYWORD, degradedLlm.degradedReason(),
                latency, null, null, null);
    }

    private void ensureNotifications(PairSide lost, PairSide found, Long pairId, BigDecimal score) {
        try {
            transactionTemplate.executeWithoutResult(status -> publishNotifications(lost, found, pairId, score));
        } catch (RuntimeException e) {
            log.warn("pair 通知补偿失败 pairId={} cause={}", pairId, e.getMessage());
        }
    }

    /**
     * 双端通知：PAIR_MATCH，唯一键 {@code (user_id, pair_id, type)} 幂等。
     *
     * <p>正文<b>不写对方标题</b>，只写"你的一条启事有一条可能相关"：通知是长期留存的数据，
     * 把对方标题（可能含地点等可识别信息）复制进去，等于给已关闭/已删除的记录留了一份
     * 不可回收的副本。要看得更细，点进 pair 详情（受同一套可见性约束）。
     */
    private void publishNotifications(PairSide lost, PairSide found, Long pairId, BigDecimal score) {
        int percent = score.multiply(BigDecimal.valueOf(100)).setScale(0, java.math.RoundingMode.HALF_UP).intValue();
        notifyOne(lost.publisherId(), pairId, percent,
                "你发布的寻物启事有一条可能相关的招领");
        if (found.publisherId() != null && !found.publisherId().equals(lost.publisherId())) {
            notifyOne(found.publisherId(), pairId, percent,
                    "你发布的招领启事有一条可能相关的寻物");
        }
    }

    private void notifyOne(Long userId, Long pairId, int percent, String headline) {
        if (userId == null) {
            return; // 迁移期历史数据可能没有 publisher_id：没有接收人就跳过，不写孤儿通知
        }
        Notification notification = new Notification();
        notification.setUserId(userId);
        notification.setPairId(pairId);
        notification.setType("PAIR_MATCH");
        notification.setTitle(MaskingPolicy.maskDescription(headline));
        notification.setBody(MaskingPolicy.maskDescription("相关度约 " + percent + "%，点击查看详情"));
        notificationMapper.insertIfAbsent(notification);
    }

    /** 单对结果。 */
    public record PairOutcome(Status status, Long pairId, BigDecimal score, String engine, String degradedReason) {

        public static PairOutcome skipped(Status status) {
            return new PairOutcome(status, null, null, null, null);
        }

        public enum Status {
            WRITTEN,
            SKIPPED_UNCHANGED,
            SKIPPED_LOCKED,
            FAILED,
        }
    }

    /** 单条记录的本轮统计。 */
    public record RecordScoreOutcome(long recordId, String pairState, int candidates, int written,
                                     int skipped, int locked, int degradedPairs, String error) {

        public static RecordScoreOutcome skipped(long recordId, String reason) {
            return new RecordScoreOutcome(recordId, null, 0, 0, 0, 0, 0, reason);
        }

        public static RecordScoreOutcome failed(long recordId, String error) {
            return new RecordScoreOutcome(recordId, null, 0, 0, 0, 0, 0, error);
        }

        public boolean failed() {
            return error != null && !"NOT_FOUND".equals(error) && !"CLOSED".equals(error)
                    && !"EXPIRED".equals(error);
        }

        public Map<String, Object> toLogMap() {
            return Map.of("recordId", recordId, "written", written, "skipped", skipped, "locked", locked);
        }
    }
}
