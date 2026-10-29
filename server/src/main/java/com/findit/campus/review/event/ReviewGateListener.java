package com.findit.campus.review.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.findit.campus.review.config.ReviewAsyncConfig;
import com.findit.campus.review.service.ReviewGateService;

/**
 * 发布事务提交后，把审核丢到独立线程池。
 *
 * <p>为什么必须 {@code AFTER_COMMIT}：提交前别的连接读不到新行，审核线程若提前跑会拿到 null
 * 并直接返回，记录永远停在 PENDING。为什么必须 {@code @Async}：LLM + 重试可达数十秒，
 * 在发布请求线程上跑会把同步 200 拖成超时。
 */
@Component
public class ReviewGateListener {

    private static final Logger log = LoggerFactory.getLogger(ReviewGateListener.class);

    private final ReviewGateService reviewGateService;

    public ReviewGateListener(ReviewGateService reviewGateService) {
        this.reviewGateService = reviewGateService;
    }

    @Async(ReviewAsyncConfig.REVIEW_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSubmitted(RecordSubmittedForReview event) {
        String previous = MDC.get("requestId");
        if (event.requestId() != null) {
            MDC.put("requestId", event.requestId());
        }
        try {
            reviewGateService.process(event.recordId(), event.requestId());
        } catch (RuntimeException e) {
            // 记录保持 PENDING；兜底调度会重投。这里绝不能把异常抛回发布线程。
            log.warn("review gate 异常 recordId={} trigger={} cause={}",
                    event.recordId(), event.trigger(), e.getMessage());
        } finally {
            if (previous == null) {
                MDC.remove("requestId");
            } else {
                MDC.put("requestId", previous);
            }
        }
    }
}
