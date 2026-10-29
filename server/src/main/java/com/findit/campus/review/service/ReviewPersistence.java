package com.findit.campus.review.service;

import java.time.LocalDateTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.findit.campus.pair.event.PairScoringRequested;
import com.findit.campus.record.mapper.RecordMapper;
import com.findit.campus.review.domain.ReviewAudit;
import com.findit.campus.review.mapper.ReviewAuditMapper;
import com.findit.campus.review.postprocess.PostprocessResult;

/**
 * 审核结论的事务边界：<b>状态更新与审计写入必须同生共死</b>。
 *
 * <p>为什么单独成类：审核链路里既有"调 LLM 失败"（不能回滚、要留痕）又有"通过/拒绝"
 * （必须原子写状态与审计）。把事务注解集中在这里，{@code ReviewGateService} 只负责
 * 重试与判定，不用记住哪个方法有事务。
 *
 * <p>所有状态 UPDATE 都带 {@code WHERE review_state='PENDING'}：人工审核与兜底调度可能并发，
 * 影响 0 行就表示"已经有别的结论了"——这时<b>不写审计、不触发打分</b>，避免出现
 * "审计说通过、实际被拒绝"的双结论。
 */
@Service
public class ReviewPersistence {

    private static final Logger log = LoggerFactory.getLogger(ReviewPersistence.class);

    private static final int REASON_MAX_LENGTH = 500;
    private static final int REASON_MAX_ITEMS = 3;

    private final RecordMapper recordMapper;
    private final ReviewAuditMapper auditMapper;
    private final ApplicationEventPublisher eventPublisher;

    public ReviewPersistence(RecordMapper recordMapper, ReviewAuditMapper auditMapper,
                             ApplicationEventPublisher eventPublisher) {
        this.recordMapper = recordMapper;
        this.auditMapper = auditMapper;
        this.eventPublisher = eventPublisher;
    }

    /** 尝试次数先落库：进程在 LLM 调用中途崩溃时，次数仍然真实，不会无限重试。 */
    @Transactional
    public void recordAttempt(long recordId, int attempts) {
        recordMapper.markReviewAttempt(recordId, attempts);
    }

    @Transactional
    public boolean approve(long recordId, PostprocessResult result, int attempts, String requestId) {
        String reason = joinReasons(result.reasons());
        if (reason.isBlank()) {
            reason = "AI 审核通过";
        }
        int affected = recordMapper.approveReview(recordId, result.title(), result.description(),
                result.location(), result.summary(), truncate(reason), result.engine(), result.model(),
                result.promptVersion(), result.latencyMs(), result.totalTokensOrNull(), attempts,
                LocalDateTime.now());
        if (affected == 0) {
            log.info("review approve skipped recordId={}（已被其它结论处理）", recordId);
            return false;
        }
        auditMapper.insert(ReviewAudit.ai(recordId, "APPROVE", "APPROVED", truncate(reason),
                result.engine(), result.model(), result.promptVersion(), result.latencyMs(),
                result.promptTokens(), result.completionTokens(), requestId));
        // 必须在**本事务内**发事件：@TransactionalEventListener(AFTER_COMMIT) 会丢弃事务外发布的事件，
        // 事务外发会让"审核通过但永远不打分"变成一个静默故障。
        eventPublisher.publishEvent(new PairScoringRequested(recordId, "review-approved", requestId));
        return true;
    }

    @Transactional
    public boolean reject(long recordId, String reason, PostprocessResult result, int attempts,
                          boolean manualRequired, String requestId) {
        int affected = recordMapper.rejectReview(recordId, truncate(reason), result.engine(), result.model(),
                result.promptVersion(), result.latencyMs(), result.totalTokensOrNull(), attempts,
                manualRequired, LocalDateTime.now());
        if (affected == 0) {
            log.info("review reject skipped recordId={}（已被其它结论处理）", recordId);
            return false;
        }
        auditMapper.insert(ReviewAudit.ai(recordId, "REJECT", "REJECTED", truncate(reason),
                result.engine(), result.model(), result.promptVersion(), result.latencyMs(),
                result.promptTokens(), result.completionTokens(), requestId));
        return true;
    }

    /** 连续失败：保持 PENDING + manual=1 + RULE_ONLY；不写 APPROVED，也绝不触发打分。 */
    @Transactional
    public void markOwnerConfirmRequired(long recordId, String reason, PostprocessResult last, int attempts,
                                         String requestId) {
        int affected = recordMapper.markOwnerConfirmRequired(recordId, truncate(reason),
                last == null ? null : last.model(),
                last == null ? null : last.promptVersion(), last == null ? null : last.latencyMs(),
                last == null ? null : last.totalTokensOrNull(), attempts);
        if (affected == 0) {
            return;
        }
        auditMapper.insert(ReviewAudit.ai(recordId, "PENDING", "PENDING", truncate(reason),
                last == null ? null : last.engine(), last == null ? null : last.model(),
                last == null ? null : last.promptVersion(), last == null ? null : last.latencyMs(),
                last == null ? null : last.promptTokens(), last == null ? null : last.completionTokens(),
                requestId));
    }

    /** 单次失败/非法输出的留痕；失败不应把主流程打挂。 */
    public void auditFailure(long recordId, String reason, PostprocessResult result, String requestId) {
        try {
            auditMapper.insert(ReviewAudit.ai(recordId, "PENDING", "PENDING", truncate(reason),
                    result == null ? null : result.engine(), result == null ? null : result.model(),
                    result == null ? null : result.promptVersion(), result == null ? null : result.latencyMs(),
                    result == null ? null : result.promptTokens(),
                    result == null ? null : result.completionTokens(), requestId));
        } catch (RuntimeException e) {
            log.warn("review audit 写入失败 recordId={} cause={}", recordId, e.getMessage());
        }
    }

    /** 本人读取原文的审计：失败只告警，不阻断本人查看自己的内容。 */
    public void auditRawView(long recordId, long actorId, String requestId) {
        try {
            auditMapper.insert(ReviewAudit.rawView(recordId, actorId, requestId));
        } catch (RuntimeException e) {
            log.warn("raw view audit 写入失败 recordId={} actorId={} cause={}", recordId, actorId, e.getMessage());
        }
    }

    /**
     * 逻辑删除时已把 {@code raw_*} 置 NULL；这里留一条「已清除」审计。
     *
     * <p>与 {@link #auditRawView} 不同，<b>本方法不吞异常</b>：清除原文却不留痕，
     * 等于隐私审计出现缺口。异常会让调用方事务回滚，删除与清除都不生效，比"删了但查无记录"安全。
     */
    public void auditRawPurge(long recordId, long actorId, String requestId) {
        auditMapper.insert(ReviewAudit.rawPurged(recordId, actorId, requestId));
    }

    /**
     * 发布者本人确认发布：<b>仅</b>用于 AI 重试耗尽、记录停在
     * {@code PENDING + review_manual_required=1} 的情况。
     *
     * <p>为什么条件里必须同时有 {@code review_manual_required=1}：AI 正常时这条路径必须不存在，
     * 否则任何 PENDING 记录都能被本人绕过 AI 审核直接上架。状态条件在 SQL 里，
     * service 的预检查只是把"不可用"翻译成 409，不是唯一防线。
     *
     * <p>放行后写入 {@code review_engine=RULE_ONLY}：隐私只经规则脱敏 + 本人自认，
     * 没有模型二次把关；后台按该值可筛出"未经 AI 审核"的记录。
     */
    @Transactional
    public boolean ownerConfirm(long recordId, long actorId, String requestId) {
        String reason = "本人确认发布：AI 审核不可用，仅经规则脱敏，不会补审";
        int affected = recordMapper.confirmOwnerReview(recordId, truncate(reason), LocalDateTime.now());
        if (affected == 0) {
            log.info("owner confirm skipped recordId={}（非待本人确认态或已被处理）", recordId);
            return false;
        }
        auditMapper.insert(ReviewAudit.ownerConfirm(recordId, actorId, truncate(reason), requestId));
        // 本人确认后照常入池、照常打分，不特殊对待；事件在事务内发，AFTER_COMMIT 读到已提交的 is_visible=1
        eventPublisher.publishEvent(new PairScoringRequested(recordId, "review-owner-confirmed", requestId));
        return true;
    }

    static String joinReasons(List<String> reasons) {
        if (reasons == null || reasons.isEmpty()) {
            return "";
        }
        return truncate(String.join("；", reasons.subList(0, Math.min(REASON_MAX_ITEMS, reasons.size()))));
    }

    static String truncate(String value) {
        if (value == null) {
            return null;
        }
        String s = value.strip();
        return s.length() <= REASON_MAX_LENGTH ? s : s.substring(0, REASON_MAX_LENGTH);
    }
}
