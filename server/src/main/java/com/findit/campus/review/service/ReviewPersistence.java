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

    /** 连续失败：保持 PENDING + manual=1；不写 review_state=APPROVED，也绝不触发打分。 */
    @Transactional
    public void markManual(long recordId, String reason, PostprocessResult last, int attempts,
                           String requestId) {
        int affected = recordMapper.markReviewManual(recordId, truncate(reason),
                last == null ? null : last.engine(), last == null ? null : last.model(),
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
     * 人工通过：不重新生成文本。提交时写入 title/description/location 的已经是
     * 规则剥离后的展示文本，人工审核确认的是"这份文本可以上架"，不是让模型再写一遍。
     */
    @Transactional
    public boolean manualApprove(long recordId, String reason, long actorId, String requestId) {
        int affected = recordMapper.markReviewApprovedManually(recordId, truncate(reason), LocalDateTime.now());
        if (affected == 0) {
            return false;
        }
        auditMapper.insert(ReviewAudit.manual(recordId, "MANUAL_APPROVE", "APPROVE", "APPROVED",
                truncate(reason), actorId, requestId));
        // 人工放行后同样要打分；事件在事务内发布，AFTER_COMMIT 监听器保证读到已提交的 is_visible=1
        eventPublisher.publishEvent(new PairScoringRequested(recordId, "review-manual-approved", requestId));
        return true;
    }

    @Transactional
    public boolean manualReject(long recordId, String reason, long actorId, String requestId) {
        int affected = recordMapper.markReviewRejectedManually(recordId, truncate(reason), LocalDateTime.now());
        if (affected == 0) {
            return false;
        }
        auditMapper.insert(ReviewAudit.manual(recordId, "MANUAL_REJECT", "REJECT", "REJECTED",
                truncate(reason), actorId, requestId));
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
