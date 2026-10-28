package com.findit.campus.pair.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.findit.campus.pair.config.PairAsyncConfig;
import com.findit.campus.pair.service.PairScoringService;
import com.findit.campus.pair.service.PairScoringService.RecordScoreOutcome;

/**
 * 把 {@link PairScoringRequested} 变成后台打分任务。
 *
 * <p><b>三重保护，缺一不可</b>：
 * <ol>
 *   <li>{@code @TransactionalEventListener(phase = AFTER_COMMIT)}：只在事务提交后触发。
 *       任务书明确"不用裸 {@code @Async} 直接调用"—— 裸调用会在事务内启动线程，
 *       子线程读不到未提交的行（更糟的是它可能读到旧行并算出旧分数）。</li>
 *   <li>{@code @Async("pairScoringExecutor")}：发布线程不等待。执行器队列满时丢弃（见配置类）。</li>
 *   <li>{@code try/catch} 兜住所有异常：异步任务抛出的异常只会在执行器里留下一条
 *       {@code Unexpected exception occurred invoking async method} 日志，业务侧完全看不到；
 *       这里显式接住并记录，保证"一次打分失败"不会变成"日志里的一段噪音"。</li>
 * </ol>
 *
 * <p>{@code fallbackExecution = false}：没有事务时事件被丢弃。当前所有发布/编辑路径都在
 * {@code @Transactional} 方法里；如果有调用方绕开事务发布事件，那说明它走错了路径，
 * 静默不执行比"在无事务上下文里打分"更安全（后者会读到一个不存在于库里的 id）。
 */
@Component
public class PairScoringEventListener {

    private static final Logger log = LoggerFactory.getLogger(PairScoringEventListener.class);

    private static final String MDC_REQUEST_ID = "requestId";

    private final PairScoringService scoringService;

    public PairScoringEventListener(PairScoringService scoringService) {
        this.scoringService = scoringService;
    }

    @Async(PairAsyncConfig.PAIR_SCORING_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = false)
    public void onRecordChanged(PairScoringRequested event) {
        String previous = MDC.get(MDC_REQUEST_ID);
        if (event.requestId() != null) {
            MDC.put(MDC_REQUEST_ID, event.requestId());
        }
        long startedAt = System.nanoTime();
        try {
            RecordScoreOutcome outcome = scoringService.scoreRecord(event.recordId(), event.trigger());
            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;
            log.info("pair job done recordId={} trigger={} state={} candidates={} written={} skipped={} locked={} elapsedMs={}",
                    event.recordId(), event.trigger(), outcome.pairState(), outcome.candidates(),
                    outcome.written(), outcome.skipped(), outcome.locked(), elapsedMs);
        } catch (RuntimeException e) {
            log.warn("pair job failed recordId={} trigger={} cause={}", event.recordId(), event.trigger(),
                    e.getMessage());
        } finally {
            if (previous == null) {
                MDC.remove(MDC_REQUEST_ID);
            } else {
                MDC.put(MDC_REQUEST_ID, previous);
            }
        }
    }
}
