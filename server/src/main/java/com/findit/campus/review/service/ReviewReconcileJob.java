package com.findit.campus.review.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.findit.campus.record.domain.Record;
import com.findit.campus.record.mapper.RecordMapper;
import com.findit.campus.review.config.ReviewProperties;

/**
 * 兜底调度：把"卡在 PENDING、未转人工、尝试次数未打满"的记录重新投给审核。
 *
 * <p>为什么需要它：异步队列满/进程在 LLM 调用中重启时，{@code AFTER_COMMIT} 事件已经丢了；
 * 没有兜底，记录会永远停在 PENDING（安全但不可用）。这里只拾取超过 5 分钟的记录，
 * 避开正在进行的请求；{@code review_attempts < max-attempts} 防止对已打满的记录无限重投。
 *
 * <p>人工标记 {@code review_manual_required=1} 的行<b>永不被本任务拾取</b>：
 * 人工还没看，AI 不能"自己又试一次然后放行"。
 *
 * <p>单实例演示形态用 {@link AtomicBoolean} 单飞即可；多实例部署要换成命名锁/任务表，
 * 这一点写在 ADR 的演进路线里，不在本类里假装已经解决。
 */
@Component
public class ReviewReconcileJob {

    private static final Logger log = LoggerFactory.getLogger(ReviewReconcileJob.class);

    private static final int COOLDOWN_MINUTES = 5;

    private final RecordMapper recordMapper;
    private final ReviewGateService reviewGateService;
    private final ReviewProperties properties;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public ReviewReconcileJob(RecordMapper recordMapper,
                              ReviewGateService reviewGateService,
                              ReviewProperties properties) {
        this.recordMapper = recordMapper;
        this.reviewGateService = reviewGateService;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${findit.review.reconcile-interval-ms:60000}")
    public void run() {
        if (!properties.aiEnabled() || !running.compareAndSet(false, true)) {
            return;
        }
        try {
            LocalDateTime before = LocalDateTime.now().minusMinutes(COOLDOWN_MINUTES);
            List<Record> candidates = recordMapper.selectReviewCandidates(before,
                    properties.getMaxAttempts(), properties.getReconcileBatchSize());
            if (candidates.isEmpty()) {
                return;
            }
            log.info("review reconcile start candidates={} maxAttempts={}", candidates.size(),
                    properties.getMaxAttempts());
            for (Record candidate : candidates) {
                try {
                    reviewGateService.process(candidate.getId(), null);
                } catch (RuntimeException e) {
                    // 单条失败不中断整批；下一轮还会再试（attempts 未打满时）
                    log.warn("review reconcile 单条失败 recordId={} cause={}", candidate.getId(), e.getMessage());
                }
            }
        } finally {
            running.set(false);
        }
    }
}
