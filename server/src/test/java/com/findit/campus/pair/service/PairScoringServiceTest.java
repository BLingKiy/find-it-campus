package com.findit.campus.pair.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import com.findit.campus.notification.mapper.NotificationMapper;
import com.findit.campus.pair.config.PairPolicyProperties;
import com.findit.campus.pair.config.PairScoreProperties;
import com.findit.campus.pair.domain.PairScore;
import com.findit.campus.pair.domain.PairSide;
import com.findit.campus.pair.engine.DegradedReason;
import com.findit.campus.pair.engine.PairContentHasher;
import com.findit.campus.pair.engine.PairScoreCommand;
import com.findit.campus.pair.engine.PairScoreResult;
import com.findit.campus.pair.engine.PairScoringMetrics;
import com.findit.campus.pair.engine.deepseek.DeepSeekPairScorer;
import com.findit.campus.pair.engine.local.LocalKeywordPairScorer;
import com.findit.campus.pair.mapper.PairScoreMapper;
import com.findit.campus.record.domain.Record;
import com.findit.campus.record.domain.RecordStatus;
import com.findit.campus.record.domain.RecordType;
import com.findit.campus.record.mapper.RecordMapper;

/**
 * 打分链路的幂等 / 降级 / 状态不可覆盖（D1 验收的核心单测）。
 *
 * <p>为什么这些用例不连数据库：它们验证的是<b>决策</b>——"哈希没变就别调 LLM""降级要写本地分"
 * "重算不能碰 status"。决策写在 {@link PairScoringService} 里，用 mock 就能逐条钉死；
 * 真正需要库的断言（IGNORED 不被覆盖、一条招领导致两行 pair）交给
 * {@code docs/testing/v2/D1-idempotency.md} 的集成证据。两者分工明确：
 * 单测保证"代码不会写错"，集成证据保证"SQL 真的没写错"。
 */
@DisplayName("PairScoringService：重复触发不重复计费 / 降级有分数 / 不覆盖 pair 状态")
class PairScoringServiceTest {

    private RecordMapper recordMapper;
    private PairScoreMapper pairScoreMapper;
    private PairRecallService recallService;
    private PairLockService lockService;
    private NotificationMapper notificationMapper;
    private DeepSeekPairScorer llmScorer;
    private LocalKeywordPairScorer localScorer;
    private PairScoringMetrics metrics;
    private PairScoringService service;

    private PairSide lostSide;
    private PairSide foundSide;

    @BeforeEach
    void setUp() {
        recordMapper = mock(RecordMapper.class);
        pairScoreMapper = mock(PairScoreMapper.class);
        recallService = mock(PairRecallService.class);
        lockService = mock(PairLockService.class);
        notificationMapper = mock(NotificationMapper.class);
        llmScorer = mock(DeepSeekPairScorer.class);
        localScorer = mock(LocalKeywordPairScorer.class);
        metrics = new PairScoringMetrics();

        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));

        service = new PairScoringService(recordMapper, pairScoreMapper, recallService, lockService,
                notificationMapper, llmScorer, localScorer, new PairPolicyProperties(),
                new PairScoreProperties(), metrics, transactionManager);

        Record lost = record(1L, RecordType.LOST, "丢失校园卡", "在图书馆二楼丢的，蓝色卡套");
        Record found = record(2L, RecordType.FOUND, "捡到一张校园卡", "图书馆二楼自习室捡到，蓝色卡套");
        when(recordMapper.selectById(1L)).thenReturn(lost);
        when(recordMapper.selectById(2L)).thenReturn(found);
        when(recordMapper.selectByIds(any())).thenReturn(List.of(found));

        lostSide = PairRecallService.toSide(lost);
        foundSide = PairRecallService.toSide(found);
        when(recallService.recall(any())).thenReturn(List.of(foundSide));
        when(lockService.tryAcquire(anyLong(), anyLong())).thenReturn(mock(PairLock.class));
        when(localScorer.score(any())).thenReturn(PairScoreResult.local(new BigDecimal("0.42"), 1));
    }

    @Test
    @DisplayName("同一 pair 连续触发 3 次（发布/扫描/手动）：只可能写 1 次，之后 LLM 调用数为 0")
    void repeatedTriggersDoNotCallLlmAgain() {
        PairScore existing = existingRow(lostHash(), foundHash(), new BigDecimal("0.90"), "DEEPSEEK");
        when(pairScoreMapper.selectByPair(1L, 2L)).thenReturn(existing);

        service.scoreRecord(1L, "publish");
        service.scoreRecord(1L, "scan");
        service.scoreRecord(1L, "manual");

        verify(llmScorer, never()).score(any());
        verify(pairScoreMapper, never()).upsertScore(any());
        assertEquals(3, metrics.skipped(), "三次都应落在 skippedUnchanged 上");
    }

    @Test
    @DisplayName("首次打分调 1 次 LLM；之后的重复触发不再调")
    void firstRunScoresThenSkips() {
        PairScore written = existingRow(lostHash(), foundHash(), new BigDecimal("0.90"), "DEEPSEEK");
        // 第 1 次：快路径 null → 锁内 null → 写入 → 事务内回读 written → 写后回读 written；之后快路径直接跳过
        when(pairScoreMapper.selectByPair(1L, 2L)).thenReturn(null, null, written, written, written, written);
        when(llmScorer.score(any())).thenReturn(
                PairScoreResult.llm(new BigDecimal("0.90"), 120, 300, 20, "高度重合"));

        service.scoreRecord(1L, "publish");
        service.scoreRecord(1L, "scan");
        service.scoreRecord(1L, "manual");

        verify(llmScorer, times(1)).score(any());
        verify(pairScoreMapper, times(1)).upsertScore(any());
    }

    @Test
    @DisplayName("标题变化（哈希变）→ 只重算这一对，写入新分数")
    void hashChangeRescores() {
        PairScore stale = existingRow("0".repeat(64), "1".repeat(64), new BigDecimal("0.40"), "DEEPSEEK");
        when(pairScoreMapper.selectByPair(1L, 2L)).thenReturn(stale);
        when(llmScorer.score(any())).thenReturn(
                PairScoreResult.llm(new BigDecimal("0.93"), 88, 410, 12, "标题几乎一致"));

        service.scorePair(lostSide, foundSide, "update");

        verify(llmScorer, times(1)).score(any());
        ArgumentCaptor<PairScore> captor = ArgumentCaptor.forClass(PairScore.class);
        verify(pairScoreMapper).upsertScore(captor.capture());
        assertEquals(new BigDecimal("0.93"), captor.getValue().getScore());
        assertEquals("DEEPSEEK", captor.getValue().getEngine());
        assertNull(captor.getValue().getDegradedReason());
        assertEquals(Integer.valueOf(88), captor.getValue().getLatencyMs());
    }

    @Test
    @DisplayName("降级：LLM 返回 TIMEOUT → 仍写分数（engine=LOCAL_KEYWORD），接口不失败")
    void degradationStillWritesScore() {
        when(pairScoreMapper.selectByPair(1L, 2L)).thenReturn(null);
        when(llmScorer.score(any())).thenReturn(PairScoreResult.degraded(DegradedReason.TIMEOUT, 50));

        PairScoringService.PairOutcome outcome = service.scorePair(lostSide, foundSide, "publish");
        assertEquals(PairScoringService.PairOutcome.Status.WRITTEN, outcome.status());

        ArgumentCaptor<PairScore> captor = ArgumentCaptor.forClass(PairScore.class);
        verify(pairScoreMapper).upsertScore(captor.capture());
        PairScore row = captor.getValue();
        assertEquals("LOCAL_KEYWORD", row.getEngine());
        assertEquals("TIMEOUT", row.getDegradedReason());
        assertEquals(new BigDecimal("0.42"), row.getScore(), "降级必须有分数，否则 /my 显示 0 条");
        assertEquals(Integer.valueOf(50), row.getLatencyMs(), "耗时记录失败那次调用的实测值");
        assertNull(row.getPromptTokens());
    }

    @Test
    @DisplayName("IGNORED 不被重算覆盖：重算行里 status/授权/附言/确认时间全部为空（由 SQL 列清单保证）")
    void ignoredStatusIsNotTouched() {
        PairScore ignored = existingRow("0".repeat(64), "1".repeat(64), new BigDecimal("0.95"), "DEEPSEEK");
        ignored.setId(101L);
        ignored.setStatus("IGNORED");
        ignored.setLostUnlockedAt(LocalDateTime.of(2026, 10, 1, 8, 0));
        ignored.setFoundUnlockNote("已线下联系");
        ignored.setLostConfirmedAt(LocalDateTime.of(2026, 10, 2, 9, 0));
        when(pairScoreMapper.selectByPair(1L, 2L)).thenReturn(ignored);
        when(llmScorer.score(any())).thenReturn(
                PairScoreResult.llm(new BigDecimal("0.96"), 60, 100, 9, "更改标题后重新评估"));

        service.scorePair(lostSide, foundSide, "update");
        service.scorePair(lostSide, foundSide, "update");

        ArgumentCaptor<PairScore> captor = ArgumentCaptor.forClass(PairScore.class);
        verify(pairScoreMapper, times(2)).upsertScore(captor.capture());
        assertEquals(2, captor.getAllValues().size(), "两次重算都写入（哈希仍未变，但冷却期外的降级重算不适用）");
        for (PairScore row : captor.getAllValues()) {
            assertNull(row.getStatus(), "打分链路从不设置 status");
            assertNull(row.getLostUnlockedAt(), "授权时间不在写入列清单里");
            assertNull(row.getFoundUnlockNote(), "附言不在写入列清单里");
            assertNull(row.getLostConfirmedAt(), "确认时间不在写入列清单里");
            assertEquals(new BigDecimal("0.96"), row.getScore(), "两次结果一致");
        }
        verify(llmScorer, times(2)).score(any());
    }

    @Test
    @DisplayName("拿不到命名锁 → 跳过，不写库、不调 LLM（另一个入口正在算同一对）")
    void lockMissSkips() {
        when(lockService.tryAcquire(1L, 2L)).thenReturn(null);

        PairScoringService.PairOutcome outcome = service.scorePair(lostSide, foundSide, "scan");

        assertEquals(PairScoringService.PairOutcome.Status.SKIPPED_LOCKED, outcome.status());
        verify(llmScorer, never()).score(any());
        verify(pairScoreMapper, never()).upsertScore(any());
        assertEquals(1L, metrics.snapshot().get("lockMisses"));
    }

    @Test
    @DisplayName("一条招领匹配两个失主：两条 pair 各自的键独立，互不影响")
    void oneFoundMatchesTwoLost() {
        when(pairScoreMapper.selectByPair(1L, 2L)).thenReturn(null);
        when(pairScoreMapper.selectByPair(3L, 2L)).thenReturn(null);
        when(llmScorer.score(any())).thenReturn(
                PairScoreResult.llm(new BigDecimal("0.91"), 70, 100, 10, "同一件物品"));

        service.scorePair(lostSide, foundSide, "publish");
        service.scorePair(new PairSide(3L, RecordType.LOST, RecordStatus.PENDING, null,
                "饭卡不见了", "蓝卡套，二食堂", "二食堂", 3L, "证件", null, 13L), foundSide, "publish");

        ArgumentCaptor<PairScore> captor = ArgumentCaptor.forClass(PairScore.class);
        verify(pairScoreMapper, times(2)).upsertScore(captor.capture());
        assertEquals(1L, captor.getAllValues().get(0).getLostId().longValue());
        assertEquals(3L, captor.getAllValues().get(1).getLostId().longValue());
        assertTrue(captor.getAllValues().stream().allMatch(row -> row.getFoundId().equals(2L)));
    }

    @Test
    @DisplayName("P2-4 过期 COMPLETED + DEGRADED：跳过，不置 SCORING、不召回、不调 LLM，且不算失败")
    void expiredCompletedIsNotRescored() {
        Record expired = record(1L, RecordType.LOST, "丢失校园卡", "在图书馆二楼丢的，蓝色卡套");
        expired.setStatus(RecordStatus.COMPLETED);
        expired.setCompletedAt(LocalDateTime.now().minusDays(31));
        expired.setPairState("DEGRADED");
        when(recordMapper.selectById(1L)).thenReturn(expired);

        PairScoringService.RecordScoreOutcome outcome = service.scoreRecord(1L, "scan");

        assertEquals("EXPIRED", outcome.error(), "跳过原因要能区分 CLOSED / EXPIRED");
        assertFalse(outcome.failed(), "过期跳过不是失败，扫描不应把它当待重试目标");
        verify(recordMapper, never()).markPairScoring(anyLong());
        verify(recallService, never()).recall(any());
        verify(llmScorer, never()).score(any());
        verify(pairScoreMapper, never()).upsertScore(any());
    }

    @Test
    @DisplayName("P2-4 保留期内 COMPLETED 仍参与召回（新口径没有误伤正常重算）")
    void completedWithinRetentionStillScored() {
        Record completed = record(1L, RecordType.LOST, "丢失校园卡", "在图书馆二楼丢的，蓝色卡套");
        completed.setStatus(RecordStatus.COMPLETED);
        completed.setCompletedAt(LocalDateTime.now().minusDays(3));
        when(recordMapper.selectById(1L)).thenReturn(completed);
        when(pairScoreMapper.selectByPair(1L, 2L))
                .thenReturn(existingRow(lostHash(), foundHash(), new BigDecimal("0.90"), "DEEPSEEK"));

        PairScoringService.RecordScoreOutcome outcome = service.scoreRecord(1L, "scan");

        assertEquals("READY", outcome.pairState());
        verify(recallService).recall(any());
        verify(recordMapper).markPairScored(eq(1L), eq("READY"), any());
    }

    // ------------------------------------------------------------------ fixtures

    private String lostHash() {
        return PairContentHasher.hash(lostSide);
    }

    private String foundHash() {
        return PairContentHasher.hash(foundSide);
    }

    private static PairScore existingRow(String lostHash, String foundHash, BigDecimal score, String engine) {
        PairScore row = new PairScore();
        row.setId(101L);
        row.setLostId(1L);
        row.setFoundId(2L);
        row.setScore(score);
        row.setEngine(engine);
        row.setLostHash(lostHash);
        row.setFoundHash(foundHash);
        row.setStatus("NEW");
        row.setUpdatedAt(LocalDateTime.now());
        return row;
    }

    private static Record record(long id, RecordType type, String title, String description) {
        Record record = new Record();
        record.setId(id);
        record.setType(type);
        record.setStatus(RecordStatus.PENDING);
        record.setTitle(title);
        record.setDescription(description);
        record.setLocation("图书馆二楼");
        record.setCategoryId(3L);
        record.setCategoryName("证件");
        record.setOccurredAt(LocalDateTime.of(2026, 10, 20, 9, 0));
        record.setPublisherId(type == RecordType.LOST ? 11L : 12L);
        return record;
    }
}
