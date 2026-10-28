package com.findit.campus.pair.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.findit.campus.common.api.ErrorCode;
import com.findit.campus.common.error.BizException;
import com.findit.campus.pair.config.LlmProperties;
import com.findit.campus.pair.config.PairPolicyProperties;
import com.findit.campus.pair.config.PairScoreProperties;
import com.findit.campus.pair.engine.LlmCircuitBreaker;
import com.findit.campus.pair.engine.PairScoringMetrics;
import com.findit.campus.pair.web.dto.PairScanResult;
import com.findit.campus.record.mapper.RecordMapper;

/**
 * 兜底扫描（架构 §5.4 + ZHYYKFX-88）：计数口径、单飞、上限、降级选材与熔断前置。
 *
 * <p>为什么单飞要单独测：两个扫描并发跑不会报错，只会<b>把 LLM 调用量悄悄翻倍</b>，
 * 而且互相抢命名锁、双双跳过 —— 这是最难从日志里看出异常的故障形态。
 * 用例用两个线程真实并发触发，断言第二个拿到 409/50006。
 */
@DisplayName("PairScanService：计数 / 单飞 409 / maxLlmCalls / 降级冷却选材 / 熔断跳过")
class PairScanServiceTest {

    private RecordMapper recordMapper;
    private PairScoringService scoringService;
    private PairScanService scanService;
    private PairScoringMetrics metrics;
    private PairScoreProperties scoreProperties;
    private LlmCircuitBreaker circuitBreaker;

    @BeforeEach
    void setUp() {
        recordMapper = mock(RecordMapper.class);
        scoringService = mock(PairScoringService.class);
        metrics = new PairScoringMetrics();
        scoreProperties = new PairScoreProperties();
        circuitBreaker = new LlmCircuitBreaker(new LlmProperties());
        scanService = new PairScanService(recordMapper, scoringService, scoreProperties,
                new PairPolicyProperties(), metrics, circuitBreaker);
        when(recordMapper.countExpiredCompleted(any())).thenReturn(7L);
    }

    @Test
    @DisplayName("输出扫描/补算/重算/跳过/到期五个数，且到期只计数")
    void aggregatesCounters() {
        when(recordMapper.selectPairScanTargets(any(), any(), any(), anyInt()))
                .thenReturn(List.of(1L, 2L, 3L));
        when(scoringService.scoreRecord(anyLong(), anyString(), any()))
                .thenReturn(outcome(1L, "READY", 2, 3, 0, 0),
                        outcome(2L, "DEGRADED", 1, 0, 1, 1),
                        outcome(3L, "READY", 0, 4, 0, 0));

        PairScanResult result = scanService.scan("http");

        assertEquals(3, result.scanned());
        assertEquals(3, result.rescored(), "written 求和");
        assertEquals(1, result.degraded(), "pair_state=DEGRADED 的记录数");
        assertEquals(8, result.skipped(), "skipped + locked 求和");
        assertEquals(7L, result.expired());
        assertFalse(result.limited());
        assertEquals("http", result.trigger());
        assertEquals("FULL", result.scope());
        assertNull(result.skipReason());
    }

    @Test
    @DisplayName("单条失败不计入 scanned（下一轮仍是候选）")
    void failedRecordIsNotCountedAsScanned() {
        when(recordMapper.selectPairScanTargets(any(), any(), any(), anyInt()))
                .thenReturn(List.of(1L, 2L));
        when(scoringService.scoreRecord(anyLong(), anyString(), any()))
                .thenReturn(PairScoringService.RecordScoreOutcome.failed(1L, "db down"),
                        outcome(2L, "READY", 1, 0, 0, 0));

        PairScanResult result = scanService.scan("scheduled");

        assertEquals(1, result.scanned());
        assertEquals(1, result.rescored());
    }

    @Test
    @DisplayName("达到 maxLlmCalls 即停，limited=true（不把配额一次烧完）")
    void stopsAtMaxLlmCalls() {
        scoreProperties.setMaxLlmCalls(2);
        when(recordMapper.selectPairScanTargets(any(), any(), any(), anyInt()))
                .thenReturn(List.of(1L, 2L, 3L));
        when(scoringService.scoreRecord(anyLong(), anyString(), any())).thenAnswer(invocation -> {
            metrics.llmCallStarted();
            return outcome(invocation.getArgument(0), "READY", 1, 0, 0, 0);
        });

        PairScanResult result = scanService.scan("http");

        assertEquals(2, result.scanned());
        assertTrue(result.limited());
    }

    @Test
    @DisplayName("并发触发：第二个拿到 409 / 50006，不会重复烧配额")
    void concurrentScanRejected() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(recordMapper.selectPairScanTargets(any(), any(), any(), anyInt()))
                .thenReturn(List.of(1L));
        when(scoringService.scoreRecord(anyLong(), anyString(), any())).thenAnswer(invocation -> {
            started.countDown();
            release.await(5, TimeUnit.SECONDS);
            return outcome(1L, "READY", 1, 0, 0, 0);
        });

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<PairScanResult> first = executor.submit(() -> scanService.scan("http"));
            assertTrue(started.await(5, TimeUnit.SECONDS), "第一轮应已进入打分阶段");

            BizException conflict = assertThrows(BizException.class, () -> scanService.scan("http"));
            assertEquals(ErrorCode.PAIR_SCAN_IN_PROGRESS, conflict.errorCode());

            release.countDown();
            assertEquals(1, first.get(5, TimeUnit.SECONDS).scanned());
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("扫描查询带保留期下界：completedCutoff ≈ now-30d，且 stale/degraded 窗口更近（P2-4）")
    void passesRetentionCutoff() {
        when(recordMapper.selectPairScanTargets(any(), any(), any(), anyInt()))
                .thenReturn(List.of());
        LocalDateTime before = LocalDateTime.now();

        scanService.scan("http");

        ArgumentCaptor<LocalDateTime> stale = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> degraded = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(recordMapper).selectPairScanTargets(stale.capture(), cutoff.capture(),
                degraded.capture(), anyInt());
        assertTrue(cutoff.getValue().isAfter(before.minusDays(31)), "保留期下界不应早于 31 天前");
        assertTrue(cutoff.getValue().isBefore(before.minusDays(29)), "保留期下界不应晚于 29 天前");
        assertTrue(stale.getValue().isAfter(cutoff.getValue()), "stale 窗口（10 分钟）远晚于保留期下界");
        assertTrue(degraded.getValue().isAfter(before.minusMinutes(61)),
                "降级冷却下界应 ≈ now-60min");
        assertTrue(degraded.getValue().isBefore(before.minusMinutes(59)),
                "降级冷却下界应 ≈ now-60min");
    }

    @Test
    @DisplayName("降级重扫走独立 SQL：只查 DEGRADED、冷却下界=now-60min、不做到期统计")
    void degradedScanUsesDedicatedQuery() {
        when(recordMapper.selectDegradedScanTargets(any(), any(), anyInt())).thenReturn(List.of(4L));
        when(scoringService.scoreRecord(anyLong(), anyString(), any()))
                .thenReturn(outcome(4L, "READY", 1, 0, 0, 0));
        LocalDateTime before = LocalDateTime.now();

        PairScanResult result = scanService.scanDegraded("http-degraded");

        ArgumentCaptor<LocalDateTime> degraded = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(recordMapper).selectDegradedScanTargets(degraded.capture(), any(), anyInt());
        verify(recordMapper, never()).selectPairScanTargets(any(), any(), any(), anyInt());
        verify(recordMapper, never()).countExpiredCompleted(any());
        assertTrue(degraded.getValue().isAfter(before.minusMinutes(61)));
        assertTrue(degraded.getValue().isBefore(before.minusMinutes(59)));
        assertEquals("DEGRADED", result.scope());
        assertEquals("http-degraded", result.trigger());
        assertEquals(1, result.scanned());
        assertEquals(-1L, result.expired(), "降级重扫不做随表增长的到期 COUNT");
    }

    @Test
    @DisplayName("降级重扫同样受 maxLlmCalls 约束：达到即停，剩余留到下一轮")
    void degradedScanStopsAtMaxLlmCalls() {
        scoreProperties.setMaxLlmCalls(1);
        when(recordMapper.selectDegradedScanTargets(any(), any(), anyInt()))
                .thenReturn(List.of(1L, 2L, 3L));
        when(scoringService.scoreRecord(anyLong(), anyString(), any())).thenAnswer(invocation -> {
            metrics.llmCallStarted();
            return outcome(invocation.getArgument(0), "READY", 1, 0, 0, 0);
        });

        PairScanResult result = scanService.scanDegraded("degraded-scheduled");

        assertEquals(1, result.scanned());
        assertTrue(result.limited());
        verify(scoringService, never()).scoreRecord(eq(2L), eq("degraded-scheduled"), any());
    }

    @Test
    @DisplayName("单条记录被预算截断：limited=true，后续记录不再处理（剩余留到下一轮）")
    void truncatedRecordStopsRound() {
        when(recordMapper.selectDegradedScanTargets(any(), any(), anyInt()))
                .thenReturn(List.of(1L, 2L));
        when(scoringService.scoreRecord(anyLong(), anyString(), any())).thenReturn(
                new PairScoringService.RecordScoreOutcome(1L, "DEGRADED", 3, 1, 0, 0, 2, null, true));

        PairScanResult result = scanService.scanDegraded("degraded-scheduled");

        assertEquals(1, result.scanned());
        assertTrue(result.limited());
        verify(scoringService, never()).scoreRecord(eq(2L), eq("degraded-scheduled"), any());
    }

    @Test
    @DisplayName("熔断打开时降级重扫整体跳过：不查库、不空转、不推后冷却时钟")
    void degradedScanSkippedWhileCircuitOpen() {
        for (int i = 0; i < new LlmProperties().getCircuitFailureThreshold(); i++) {
            circuitBreaker.recordFailure();
        }
        assertTrue(circuitBreaker.isOpen());

        PairScanResult result = scanService.scanDegraded("degraded-scheduled");

        assertEquals("CIRCUIT_OPEN", result.skipReason());
        assertEquals(0, result.scanned());
        verify(recordMapper, never()).selectDegradedScanTargets(any(), any(), anyInt());
        verify(scoringService, never()).scoreRecord(anyLong(), anyString(), any());
    }

    @Test
    @DisplayName("降级重扫与全量扫描共用单飞：撞车时抛 409（HTTP 入口语义不变）")
    void degradedScanSharesSingleFlight() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(recordMapper.selectPairScanTargets(any(), any(), any(), anyInt()))
                .thenReturn(List.of(1L));
        when(scoringService.scoreRecord(anyLong(), anyString(), any())).thenAnswer(invocation -> {
            started.countDown();
            release.await(5, TimeUnit.SECONDS);
            return outcome(1L, "READY", 1, 0, 0, 0);
        });

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<PairScanResult> full = executor.submit(() -> scanService.scan("http"));
            assertTrue(started.await(5, TimeUnit.SECONDS));
            BizException conflict = assertThrows(BizException.class,
                    () -> scanService.scanDegraded("http-degraded"));
            assertEquals(ErrorCode.PAIR_SCAN_IN_PROGRESS, conflict.errorCode());
            release.countDown();
            full.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    private static PairScoringService.RecordScoreOutcome outcome(long recordId, String state, int written,
                                                                 int skipped, int locked, int degradedPairs) {
        return new PairScoringService.RecordScoreOutcome(recordId, state, written + skipped, written,
                skipped, locked, degradedPairs, null);
    }
}
