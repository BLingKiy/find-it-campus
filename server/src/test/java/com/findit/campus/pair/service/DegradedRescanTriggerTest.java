package com.findit.campus.pair.service;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.findit.campus.pair.config.LlmProperties;
import com.findit.campus.pair.engine.LlmCircuitBreaker;
import com.findit.campus.pair.web.dto.PairScanResult;

/**
 * 熔断恢复 → 降级重扫的事件驱动接线（ZHYYKFX-88 §3）。
 *
 * <p>三个验收点：① 恢复事件真的能触发一次 {@code scanDegraded}；② 回调不阻塞调用线程；
 * ③ 扫描失败不冒泡、也不影响下一次恢复。用 {@code timeout} 等待异步结果，不用 sleep
 * 猜时间。
 */
@DisplayName("DegradedRescanTrigger：恢复事件触发一次降级重扫 / 异步 / 失败不冒泡")
class DegradedRescanTriggerTest {

    @Test
    @DisplayName("熔断 HALF_OPEN 探测成功 → CLOSED 时提交一次 scanDegraded(llm-recovered)")
    void recoveryFiresOneDegradedScan() {
        PairScanService scanService = mock(PairScanService.class);
        when(scanService.scanDegraded("llm-recovered"))
                .thenReturn(PairScanResult.skipped("llm-recovered", "DEGRADED", null, 1L, Map.of()));

        LlmProperties properties = new LlmProperties();
        properties.setCircuitFailureThreshold(1);
        // 冷却设为 0：让测试立刻进入 HALF_OPEN，不必等 5 分钟
        properties.setCircuitOpenDuration(Duration.ZERO);
        LlmCircuitBreaker breaker = new LlmCircuitBreaker(properties);
        DegradedRescanTrigger trigger = new DegradedRescanTrigger(breaker, scanService);
        try {
            trigger.register();

            breaker.recordFailure();   // 打开
            assertTrue(breaker.allowRequest(), "冷却为 0，应立即放行探测");
            breaker.recordSuccess();   // 探测成功 → CLOSED → 恢复回调

            verify(scanService, timeout(2_000).times(1)).scanDegraded("llm-recovered");
        } finally {
            trigger.shutdown();
        }
    }

    @Test
    @DisplayName("同一时刻只排一轮：第一轮还在跑时重复恢复回调被丢弃")
    void duplicateRecoveryIsDeduplicated() throws Exception {
        PairScanService scanService = mock(PairScanService.class);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        when(scanService.scanDegraded(any())).thenAnswer(invocation -> {
            started.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } finally {
                finished.countDown();
            }
            return PairScanResult.skipped("llm-recovered", "DEGRADED", null, 1L, Map.of());
        });
        DegradedRescanTrigger trigger = new DegradedRescanTrigger(mock(LlmCircuitBreaker.class), scanService);
        try {
            trigger.onLlmRecovered();
            assertTrue(started.await(5, TimeUnit.SECONDS), "第一轮应已开始");

            trigger.onLlmRecovered();   // 在飞：应被丢弃

            verify(scanService, times(1)).scanDegraded(eq("llm-recovered"));
            release.countDown();
            assertTrue(finished.await(5, TimeUnit.SECONDS), "第一轮应结束");
            verify(scanService, times(1)).scanDegraded("llm-recovered");
        } finally {
            release.countDown();
            trigger.shutdown();
        }
    }

    @Test
    @DisplayName("扫描抛异常不外泄；下一轮恢复仍能正常触发")
    void failureDoesNotPropagateAndNextRecoveryStillRuns() {
        PairScanService scanService = mock(PairScanService.class);
        when(scanService.scanDegraded("llm-recovered"))
                .thenThrow(new IllegalStateException("db down"))
                .thenReturn(PairScanResult.skipped("llm-recovered", "DEGRADED", null, 1L, Map.of()));
        DegradedRescanTrigger trigger = new DegradedRescanTrigger(mock(LlmCircuitBreaker.class), scanService);
        try {
            trigger.onLlmRecovered();
            verify(scanService, timeout(2_000).times(1)).scanDegraded("llm-recovered");

            // 等第一轮把 inFlight 复位：失败路径的 finally 总是执行
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (System.nanoTime() < deadline) {
                trigger.onLlmRecovered();
                try {
                    verify(scanService, timeout(300).times(2)).scanDegraded("llm-recovered");
                    return;
                } catch (AssertionError ignored) {
                    // 第一轮还没复位，重试
                }
            }
            throw new AssertionError("失败后下一轮恢复重扫未触发");
        } finally {
            trigger.shutdown();
        }
    }
}
