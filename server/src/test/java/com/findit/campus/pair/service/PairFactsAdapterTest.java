package com.findit.campus.pair.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.findit.campus.pair.domain.PairCardRow;
import com.findit.campus.pair.mapper.PairFactsMapper;
import com.findit.campus.record.domain.PairCardFacts;

/**
 * 卡片装配的边界：逐端确认/授权的独立性、{@code otherNote} 的可见条件、三档与可用性。
 *
 * <p>这些判定如果写错，表现是"卡片说对方已公开、点进去却 403"或"直看档看不到附言"，
 * 都属于用户能直接撞上的自相矛盾。DAO 用 mock，本类只验装配逻辑（SQL 过滤由
 * {@code PairFactsCardsSqlTest} 与真实库证据覆盖）。
 */
@DisplayName("PairFactsAdapter：分组 / 逐端标志 / otherNote 门控 / 三档")
class PairFactsAdapterTest {

    private static final BigDecimal UNLOCK = new BigDecimal("0.75");
    private static final BigDecimal DIRECT = new BigDecimal("0.90");
    private static final LocalDateTime CUTOFF = LocalDateTime.of(2026, 9, 27, 12, 0);

    private PairFactsMapper mapper;
    private PairFactsAdapter adapter;

    @BeforeEach
    void setUp() {
        mapper = mock(PairFactsMapper.class);
        adapter = new PairFactsAdapter(mapper);
    }

    @Test
    @DisplayName("空 id 集合：一次查询都不发（不是发一条 IN () 的坏 SQL）")
    void emptyIdsDoNotQuery() {
        assertTrue(adapter.cardsOf(List.of(), UNLOCK, DIRECT, CUTOFF).isEmpty());
        assertTrue(adapter.cardsOf(null, UNLOCK, DIRECT, CUTOFF).isEmpty());
        verifyNoInteractions(mapper);
    }

    @Test
    @DisplayName("按 recordId 分组且保持 SQL 顺序（score 降序、同分 pairId 升序）")
    void groupsByRecordPreservingOrder() {
        when(mapper.selectCardsByRecordIds(any(), any(), any())).thenReturn(List.of(
                row(1L, 101L, "0.9200", "LOST"),
                row(1L, 102L, "0.8000", "LOST"),
                row(2L, 103L, "0.9000", "FOUND")));

        Map<Long, List<PairCardFacts>> cards = adapter.cardsOf(List.of(1L, 2L, 3L), UNLOCK, DIRECT, CUTOFF);

        assertEquals(List.of(101L, 102L),
                cards.get(1L).stream().map(PairCardFacts::pairId).toList());
        assertEquals(1, cards.get(2L).size());
        assertNull(cards.get(3L), "没有卡片的记录不出现，调用方用空列表兜底");
        assertEquals(92, cards.get(1L).get(0).scorePercent());
        assertEquals("DIRECT", cards.get(1L).get(0).tier());
        assertEquals("AUTHORIZABLE", cards.get(1L).get(1).tier());
    }

    @Test
    @DisplayName("同一条 pair 两端同主：两个 recordId 下 mySide / 确认 / 授权 / 附言各自独立")
    void bothEndsOwnedBySameUserStayIndependent() {
        PairCardRow lostSide = row(1L, 101L, "0.8000", "LOST");
        lostSide.setLostConfirmedAt(LocalDateTime.of(2026, 10, 27, 9, 0));
        lostSide.setLostUnlockedAt(LocalDateTime.of(2026, 10, 27, 9, 5));
        lostSide.setLostUnlockNote("本端附言");
        lostSide.setFoundUnlockNote("对方附言");
        PairCardRow foundSide = row(2L, 101L, "0.8000", "FOUND");
        foundSide.setLostConfirmedAt(lostSide.getLostConfirmedAt());
        foundSide.setLostUnlockedAt(lostSide.getLostUnlockedAt());
        foundSide.setLostUnlockNote("本端附言");
        foundSide.setFoundUnlockNote("对方附言");
        when(mapper.selectCardsByRecordIds(any(), any(), any())).thenReturn(List.of(lostSide, foundSide));

        Map<Long, List<PairCardFacts>> cards = adapter.cardsOf(List.of(1L, 2L), UNLOCK, DIRECT, CUTOFF);
        PairCardFacts mine = cards.get(1L).get(0);
        PairCardFacts theirs = cards.get(2L).get(0);

        assertEquals("LOST", mine.mySide());
        assertTrue(mine.myConfirmed());
        assertFalse(mine.otherConfirmed());
        assertTrue(mine.myUnlocked());
        assertFalse(mine.otherUnlocked());
        assertEquals("本端附言", mine.myNote());
        assertNull(mine.otherNote(), "对方未授权 → otherNote 必须为 null");

        assertEquals("FOUND", theirs.mySide());
        assertFalse(theirs.myConfirmed());
        assertTrue(theirs.otherConfirmed());
        assertFalse(theirs.myUnlocked());
        assertTrue(theirs.otherUnlocked());
        assertEquals("对方附言", theirs.myNote());
        assertEquals("本端附言", theirs.otherNote(), "对方端已授权 → 可以看对方附言");
    }

    @Test
    @DisplayName("otherNote 门控：AUTHORIZABLE 未授权 → null；直看档即使未授权也给")
    void otherNoteVisibility() {
        PairCardRow authorizable = row(1L, 101L, "0.8000", "LOST");
        authorizable.setFoundUnlockNote("对方的附言");
        when(mapper.selectCardsByRecordIds(any(), any(), any())).thenReturn(List.of(authorizable));
        assertNull(adapter.cardsOf(List.of(1L), UNLOCK, DIRECT, CUTOFF).get(1L).get(0).otherNote());
        assertNull(adapter.cardsOf(List.of(1L), UNLOCK, DIRECT, CUTOFF).get(1L).get(0).myNote());

        PairCardRow direct = row(1L, 102L, "0.9200", "LOST");
        direct.setFoundUnlockNote("对方的附言");
        when(mapper.selectCardsByRecordIds(any(), any(), any())).thenReturn(List.of(direct));
        assertEquals("对方的附言",
                adapter.cardsOf(List.of(1L), UNLOCK, DIRECT, CUTOFF).get(1L).get(0).otherNote());
    }

    @Test
    @DisplayName("§5.1 contactAvailable / contactHint：未公开 / 已公开 / 未填写")
    void contactAvailabilityAndHint() {
        // AUTHORIZABLE + 未授权 + 有号
        PairCardRow notPublic = row(1L, 101L, "0.8000", "LOST");
        notPublic.setCounterpartContactPresent(true);
        when(mapper.selectCardsByRecordIds(any(), any(), any())).thenReturn(List.of(notPublic));
        PairCardFacts facts = adapter.cardsOf(List.of(1L), UNLOCK, DIRECT, CUTOFF).get(1L).get(0);
        assertFalse(facts.contactAvailable());
        assertEquals("对方尚未公开", facts.contactHint());

        // AUTHORIZABLE + 已授权 + 有号
        PairCardRow published = row(1L, 102L, "0.8000", "LOST");
        published.setFoundUnlockedAt(LocalDateTime.of(2026, 10, 27, 10, 0));
        published.setCounterpartContactPresent(true);
        when(mapper.selectCardsByRecordIds(any(), any(), any())).thenReturn(List.of(published));
        facts = adapter.cardsOf(List.of(1L), UNLOCK, DIRECT, CUTOFF).get(1L).get(0);
        assertTrue(facts.contactAvailable());
        assertEquals("对方已公开联系方式", facts.contactHint());

        // AUTHORIZABLE + 已授权 + 没填号
        PairCardRow noNumber = row(1L, 103L, "0.8000", "LOST");
        noNumber.setFoundUnlockedAt(LocalDateTime.of(2026, 10, 27, 10, 0));
        noNumber.setCounterpartContactPresent(false);
        when(mapper.selectCardsByRecordIds(any(), any(), any())).thenReturn(List.of(noNumber));
        facts = adapter.cardsOf(List.of(1L), UNLOCK, DIRECT, CUTOFF).get(1L).get(0);
        assertFalse(facts.contactAvailable());
        assertEquals("对方未填写联系方式", facts.contactHint());

        // DIRECT + 没填号：按钮不可点，文案不能是"可直接查看"
        PairCardRow directNoNumber = row(1L, 104L, "0.9500", "LOST");
        directNoNumber.setCounterpartContactPresent(false);
        when(mapper.selectCardsByRecordIds(any(), any(), any())).thenReturn(List.of(directNoNumber));
        facts = adapter.cardsOf(List.of(1L), UNLOCK, DIRECT, CUTOFF).get(1L).get(0);
        assertFalse(facts.contactAvailable());
        assertEquals("对方未填写联系方式", facts.contactHint());
    }

    @Test
    @DisplayName("MATCHED / IGNORED 照常出卡；engine=LOCAL_KEYWORD → degraded")
    void statusAndEngineFlags() {
        PairCardRow matched = row(1L, 101L, "0.9200", "LOST");
        matched.setStatus("MATCHED");
        matched.setLostConfirmedAt(LocalDateTime.of(2026, 10, 27, 9, 0));
        matched.setFoundConfirmedAt(LocalDateTime.of(2026, 10, 27, 9, 10));
        PairCardRow ignored = row(1L, 102L, "0.8000", "LOST");
        ignored.setStatus("IGNORED");
        ignored.setEngine("LOCAL_KEYWORD");
        when(mapper.selectCardsByRecordIds(any(), any(), any())).thenReturn(List.of(matched, ignored));

        List<PairCardFacts> cards = adapter.cardsOf(List.of(1L), UNLOCK, DIRECT, CUTOFF).get(1L);

        assertTrue(cards.get(0).matched());
        assertFalse(cards.get(0).degraded());
        assertEquals("IGNORED", cards.get(1).status());
        assertFalse(cards.get(1).matched());
        assertTrue(cards.get(1).degraded());
    }

    private static PairCardRow row(long recordId, long pairId, String score, String mySide) {
        PairCardRow row = new PairCardRow();
        row.setRecordId(recordId);
        row.setPairId(pairId);
        row.setScore(new BigDecimal(score));
        row.setStatus("NEW");
        row.setEngine("DEEPSEEK");
        row.setMySide(mySide);
        row.setCounterpartRecordId(recordId + 100);
        row.setCounterpartType("FOUND");
        row.setCounterpartTitle("图书馆拾到黑色钱包");
        row.setCounterpartLocation("图书馆三楼自习区");
        row.setCounterpartOccurredAt(LocalDateTime.of(2026, 10, 20, 15, 0));
        row.setCounterpartCreatedAt(LocalDateTime.of(2026, 10, 20, 15, 30));
        row.setCounterpartStatus("PENDING");
        return row;
    }
}
