package com.findit.campus.pair.service;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.findit.campus.common.error.BizException;
import com.findit.campus.pair.engine.LlmCircuitBreaker;
import com.findit.campus.pair.web.dto.PairScanResult;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

/**
 * 熔断恢复 → 触发一次降级重扫（ZHYYKFX-88 §3，事件驱动，最省额度）。
 *
 * <p><b>为什么挂恢复事件而不是缩短定时周期</b>：定时器不知道 LLM 什么时候好；把周期缩到 1 分钟
 * 只会让"不可用期间"多跑 10 倍空转 SQL。恢复事件是唯一"确知通路已恢复"的时刻 —— 此刻重扫，
 * 既能立刻出分，又不会在故障期间浪费一次调用。
 *
 * <p><b>为什么异步</b>：回调在打分线程上执行，而那把线程正持有某条 pair 的 MySQL 命名锁。
 * 如果在这里同步跑扫描（可能几分钟），会把一条库连接连同锁一起占住，还会与当前记录自锁。
 * 这里只做一次 `executor.execute`，回调立即返回。
 *
 * <p><b>为什么最多一个在飞</b>：{@link AtomicBoolean} 去重。熔断抖动（恢复→失败→恢复）可以
 * 在几分钟内触发多次回调；排队多轮扫描没有意义，因为后一轮会看到同样的候选。
 * 被丢掉的那次由 10 分钟定时降级重扫兜底 —— 这条链路允许丢事件，只要不丢数据。
 *
 * <p><b>失败只记日志</b>：恢复扫描不是主流程的一部分。它失败不能让探测调用的结果变红，
 * 也不能影响正在打分的记录；下一轮定时重扫会重试，而候选是由数据库状态决定的，不是事件。
 */
@Component
public class DegradedRescanTrigger {

    private static final Logger log = LoggerFactory.getLogger(DegradedRescanTrigger.class);

    /** 触发来源标识：进扫描日志与响应，便于把"恢复触发的重扫"和定时重扫分开统计。 */
    public static final String TRIGGER_LLM_RECOVERED = "llm-recovered";

    private final LlmCircuitBreaker circuitBreaker;
    private final PairScanService scanService;
    private final AtomicBoolean inFlight = new AtomicBoolean(false);

    private final ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "pair-degraded-rescan");
        thread.setDaemon(true);
        return thread;
    });

    public DegradedRescanTrigger(LlmCircuitBreaker circuitBreaker, PairScanService scanService) {
        this.circuitBreaker = circuitBreaker;
        this.scanService = scanService;
    }

    @PostConstruct
    void register() {
        circuitBreaker.addRecoveryListener(this::onLlmRecovered);
    }

    /**
     * 恢复回调本体：只负责提交，不跑扫描。
     *
     * <p>包级可见是为了让单测能直接触发，不必真的把熔断器打开再等 5 分钟。
     */
    void onLlmRecovered() {
        if (!inFlight.compareAndSet(false, true)) {
            log.info("LLM 恢复触发的降级重扫已有一轮在排队/执行，跳过本次回调");
            return;
        }
        try {
            executor.execute(this::rescanOnce);
            log.info("LLM 恢复事件：已提交一次降级重扫（trigger={}）", TRIGGER_LLM_RECOVERED);
        } catch (RejectedExecutionException e) {
            inFlight.set(false);
            log.warn("LLM 恢复触发的降级重扫提交失败（线程池已关闭）cause={}", e.getMessage());
        }
    }

    private void rescanOnce() {
        try {
            PairScanResult result = scanService.scanDegraded(TRIGGER_LLM_RECOVERED);
            log.info("pair degraded rescan on recovery done trigger={} scanned={} rescored={} degraded={} skipped={} limited={} skipReason={} elapsedMs={}",
                    result.trigger(), result.scanned(), result.rescored(), result.degraded(), result.skipped(),
                    result.limited(), result.skipReason(), result.elapsedMs());
        } catch (BizException e) {
            // 与定时/全量扫描撞车：候选由数据库状态决定，下一轮会覆盖，不算失败
            log.info("pair degraded rescan on recovery skipped reason=IN_PROGRESS code={}", e.errorCode().code());
        } catch (RuntimeException e) {
            // 重算失败不影响刚刚恢复的主流程（探测调用的结果已经落库）
            log.warn("pair degraded rescan on recovery failed cause={}", e.getMessage());
        } finally {
            inFlight.set(false);
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
