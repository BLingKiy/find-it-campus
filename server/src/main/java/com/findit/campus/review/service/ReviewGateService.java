package com.findit.campus.review.service;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.findit.campus.record.domain.Record;
import com.findit.campus.record.domain.RecordReviewState;
import com.findit.campus.record.domain.RecordType;
import com.findit.campus.record.mapper.RecordMapper;
import com.findit.campus.review.config.ReviewProperties;
import com.findit.campus.review.postprocess.IdentityScrubber;
import com.findit.campus.review.postprocess.PostprocessClient;
import com.findit.campus.review.postprocess.PostprocessCommand;
import com.findit.campus.review.postprocess.PostprocessPrompt;
import com.findit.campus.review.postprocess.PostprocessResult;
import com.findit.campus.review.postprocess.SensitiveTextScanner;

/**
 * 发布闸门的编排：读记录 → 调 LLM → 规则双检 → 落结论 →（通过才）触发配对。
 *
 * <p>失败策略（人类已确认的默认口径）：
 * <ol>
 *   <li>AI 不可用/超时/输出不完整 → 重试 {@code max-attempts} 次，指数退避；</li>
 *   <li>仍失败 → 记录停在 {@code PENDING} + {@code review_manual_required=1}，
 *       <b>绝不自动上架</b>；不删、不过期；</li>
 *   <li>AI 明确 REJECT 或规则双检命中 → 立即 REJECTED，不重试（重试不会改变确定性规则的结果）。</li>
 * </ol>
 *
 * <p>为什么规则双检在 AI 之后：模型是概率组件，prompt 只能降低残留概率；确定性扫描是唯一的
 * 放行凭证。命中即拒绝，<b>模型说通过也不算数</b>。
 */
@Service
public class ReviewGateService {

    private static final Logger log = LoggerFactory.getLogger(ReviewGateService.class);

    private static final int TITLE_MIN = 2;
    private static final int TITLE_MAX = 64;
    private static final int DESC_MIN = 2;
    private static final int DESC_MAX = 2000;
    private static final int LOCATION_MAX = 128;

    private final RecordMapper recordMapper;
    private final PostprocessClient client;
    private final ReviewPersistence persistence;
    private final ReviewProperties properties;

    public ReviewGateService(RecordMapper recordMapper,
                             PostprocessClient client,
                             ReviewPersistence persistence,
                             ReviewProperties properties) {
        this.recordMapper = recordMapper;
        this.client = client;
        this.persistence = persistence;
        this.properties = properties;
    }

    /**
     * 处理一条待审记录。可被异步监听器与兜底调度同时调用：状态更新带
     * {@code WHERE review_state='PENDING'}，并发只有一个赢家。
     *
     * @param requestId 触发请求的 id，只进审计/日志；异步线程没有 MDC，不能依赖 RequestContext
     */
    public void process(long recordId, String requestId) {
        if (!properties.aiEnabled()) {
            return;
        }
        Record record = recordMapper.selectById(recordId);
        if (record == null || record.getReviewState() != RecordReviewState.PENDING) {
            return;
        }
        if (Boolean.TRUE.equals(record.getReviewManualRequired())) {
            return; // 人工队列的行永不被自动重试，避免"人还没看，AI 先放行"
        }

        PostprocessCommand command = commandOf(record);
        int startAttempt = record.getReviewAttempts() == null ? 0 : record.getReviewAttempts();
        PostprocessResult last = null;
        for (int attempt = startAttempt + 1; attempt <= properties.getMaxAttempts(); attempt++) {
            persistence.recordAttempt(recordId, attempt);
            last = client.process(command);
            Outcome outcome = decide(record, last);
            switch (outcome.kind()) {
                case APPROVE -> {
                    if (persistence.approve(recordId, last, attempt, requestId)) {
                        log.info("review approved recordId={} attempts={} engine={} model={} latencyMs={} tokens={}",
                                recordId, attempt, last.engine(), last.model(), last.latencyMs(),
                                last.totalTokensOrNull());
                    }
                    return;
                }
                case REJECT -> {
                    persistence.reject(recordId, outcome.reason(), last, attempt, false, requestId);
                    log.info("review rejected recordId={} attempts={} reason={}", recordId, attempt,
                            outcome.reason());
                    return;
                }
                case RETRY -> {
                    persistence.auditFailure(recordId, outcome.reason(), last, requestId);
                    log.warn("review retry recordId={} attempt={}/{} reason={}", recordId, attempt,
                            properties.getMaxAttempts(), outcome.reason());
                    if (attempt < properties.getMaxAttempts()) {
                        sleepBackoff(attempt);
                    }
                }
            }
        }

        String reason = "AI 审核连续失败（" + (last == null ? "UNKNOWN" : last.errorReason())
                + "），记录保持待审并转人工；不会自动上架";
        persistence.markManual(recordId, reason, last, properties.getMaxAttempts(), requestId);
        log.warn("review manual required recordId={} attempts={} lastReason={}", recordId,
                properties.getMaxAttempts(), last == null ? "UNKNOWN" : last.errorReason());
    }

    private PostprocessCommand commandOf(Record record) {
        // 送入 LLM 的身份类信息在这里清零：name/contact/publisherId 不在 command 里，
        // 文本再过一次 scrub（历史行/mode=off 数据可能没有 match_*）。
        String title = IdentityScrubber.scrub(record.matchTitleOrDefault(), record.getPublisherName());
        String description = IdentityScrubber.scrub(record.matchDescriptionOrDefault(), record.getPublisherName());
        String location = IdentityScrubber.scrub(record.matchLocationOrDefault(), record.getPublisherName());
        return new PostprocessCommand(record.getId(), record.getType(), record.getCategoryName(),
                title, description, location, record.getOccurredAt(), properties.getPromptVersion());
    }

    private Outcome decide(Record record, PostprocessResult result) {
        if (result == null || !result.ok()) {
            return Outcome.retry(result == null ? "NO_RESULT" : result.errorReason());
        }
        // 硬规则双检先于 decision：**无论模型说 APPROVE 还是 REJECT**，只要输出里还有
        // 手机号/门牌等确定性命中，理由就必须是「规则双检命中」。模型拒绝不能替规则背书。
        List<String> hits = new ArrayList<>();
        hits.addAll(SensitiveTextScanner.scan(result.title(), record.getPublisherName()));
        hits.addAll(SensitiveTextScanner.scan(result.description(), record.getPublisherName()));
        hits.addAll(SensitiveTextScanner.scan(result.location(), record.getPublisherName()));
        hits.addAll(SensitiveTextScanner.scan(result.summary(), record.getPublisherName()));
        if (!hits.isEmpty()) {
            return Outcome.reject("规则双检命中：" + SensitiveTextScanner.describeHits(hits));
        }
        if (result.decision() == PostprocessResult.Decision.REJECT) {
            String reason = ReviewPersistence.joinReasons(result.reasons());
            return Outcome.reject(reason.isBlank() ? "AI 审核拒绝" : reason);
        }
        // decision=APPROVE 且规则不命中：再验字段完整性/长度。
        String invalid = invalidOutput(result);
        if (invalid != null) {
            return Outcome.retry(invalid);
        }
        return Outcome.approve();
    }

    private String invalidOutput(PostprocessResult result) {
        if (result.title() == null || result.description() == null
                || result.location() == null || result.summary() == null) {
            return "INCOMPLETE_OUTPUT";
        }
        if (codePoints(result.title()) < TITLE_MIN || codePoints(result.title()) > TITLE_MAX) {
            return "TITLE_LENGTH";
        }
        if (codePoints(result.description()) < DESC_MIN || codePoints(result.description()) > DESC_MAX) {
            return "DESCRIPTION_LENGTH";
        }
        if (codePoints(result.location()) < 1 || codePoints(result.location()) > LOCATION_MAX) {
            return "LOCATION_LENGTH";
        }
        if (result.summary().isBlank()
                || codePoints(result.summary()) > properties.getSummaryMaxCodePoints()) {
            return "SUMMARY_INVALID";
        }
        return null;
    }

    private static int codePoints(String value) {
        return value.codePointCount(0, value.length());
    }

    private void sleepBackoff(int attempt) {
        long delay = properties.getRetryBackoffMs() * (1L << Math.min(attempt - 1, 4));
        if (delay <= 0) {
            return;
        }
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private record Outcome(Kind kind, String reason) {

        enum Kind {
            APPROVE,
            REJECT,
            RETRY
        }

        static Outcome approve() {
            return new Outcome(Kind.APPROVE, null);
        }

        static Outcome reject(String reason) {
            return new Outcome(Kind.REJECT, reason);
        }

        static Outcome retry(String reason) {
            return new Outcome(Kind.RETRY, reason == null ? "UNKNOWN" : reason);
        }
    }
}
