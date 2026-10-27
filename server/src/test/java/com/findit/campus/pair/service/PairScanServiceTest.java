package com.findit.campus.pair.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.findit.campus.common.api.ErrorCode;
import com.findit.campus.common.error.BizException;
import com.findit.campus.pair.config.PairPolicyProperties;
import com.findit.campus.pair.config.PairScoreProperties;
import com.findit.campus.pair.engine.PairScoringMetrics;
import com.findit.campus.pair.web.dto.PairScanResult;
import com.findit.campus.record.mapper.RecordMapper;

/**
 * 兜底扫描（架构 §5.4）：计数口径、单飞、上限。
 *
 * <p>为什么单飞要单独测：两个扫描并发跑不会报错，只会<b>把 LLM 调用量悄悄翻倍</b>，
 * 而且互相抢命名锁、双双跳过 —— 这是最难从日志里看出异常的故障形态。
 * 用例用两个线程真实并发触发，断言第二个拿到 409/50006。
 */
@DisplayName("PairScanService：计数 / 单飞 409 / maxLlmCalls 提前停止")
class PairScanServiceTest {

    private RecordMapper recordMapper;
    private PairScoringService scoringService;
    private PairScanService scanService;
    private PairScoringMetrics metrics;
    private PairScoreProperties scoreProperties;

    @BeforeEach
    void setUp() {
        recordMapper = mock(RecordMapper.class);
        scoringService = mock(PairScoringService.class);
        metrics = new PairScoringMetrics();
        scoreProperties = new PairScoreProperties();
        scanService = new PairScanService(recordMapper, scoringService, scoreProperties,
                new PairPolicyProperties(), metrics);
        when(recordMapper.countExpiredCompleted(org.mockito.ArgumentMatchers.any())).thenReturn(7L);
    }

    @Test
    @DisplayName("输出扫描/补算/重算/跳过/到期五个数，且到期只计数")
    void aggregatesCounters() {
        when(recordMapper.selectPairScanTargets(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of(1L, 2L, 3L));
        when(scoringService.scoreRecord(anyLong(), anyString()))
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
    }

    @Test
    @DisplayName("单条失败不计入 scanned（下一轮仍是候选）")
    void failedRecordIsNotCountedAsScanned() {
        when(recordMapper.selectPairScanTargets(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of(1L, 2L));
        when(scoringService.scoreRecord(anyLong(), anyString()))
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
        when(recordMapper.selectPairScanTargets(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of(1L, 2L, 3L));
        when(scoringService.scoreRecord(anyLong(), anyString())).thenAnswer(invocation -> {
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
        when(recordMapper.selectPairScanTargets(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of(1L));
        when(scoringService.scoreRecord(anyLong(), anyString())).thenAnswer(invocation -> {
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

    private static PairScoringService.RecordScoreOutcome outcome(long recordId, String state, int written,
                                                                 int skipped, int locked, int degradedPairs) {
        return new PairScoringService.RecordScoreOutcome(recordId, state, written + skipped, written,
                skipped, locked, degradedPairs, null);
    }
}
