package com.findit.campus.record.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.findit.campus.common.api.PageResult;
import com.findit.campus.pair.config.PairPolicyProperties;
import com.findit.campus.record.domain.MyPairCounts;
import com.findit.campus.record.domain.MyRecordRow;
import com.findit.campus.record.domain.RecordStatus;
import com.findit.campus.record.domain.RecordType;
import com.findit.campus.record.mapper.RecordMapper;
import com.findit.campus.record.web.dto.MyRecordListItemResponse;
import com.findit.campus.record.web.dto.MyRecordsQuery;
import com.findit.campus.support.PairCardFixtures;

/**
 * {@code /my} 的装配：一页记录只发"计数 1 条 + 卡片 1 条"，且每条记录的卡片互不串。
 *
 * <p>为什么在 service 层钉这个：N+1 不会让任何单条断言失败，只会让页大小 50 时尾延迟翻倍；
 * 用 {@code verify(...)} 断言"批量方法只被调用一次、入参是整页 id"才拦得住后来有人改成
 * {@code rows.stream().map(row -> cardsOf(List.of(row.getId())))}。
 */
@DisplayName("MyRecordService：批量取卡片，查询条数与页大小无关")
class MyRecordServiceTest {

    private RecordMapper recordMapper;
    private PairFactsPort pairFactsPort;
    private MyRecordService service;

    @BeforeEach
    void setUp() {
        recordMapper = mock(RecordMapper.class);
        pairFactsPort = mock(PairFactsPort.class);
        service = new MyRecordService(recordMapper, pairFactsPort, new PairPolicyProperties());
    }

    @Test
    @DisplayName("一页 3 条（1 条无卡片）：卡片各自正确，计数与卡片各只查一次")
    void pageRowsMapPairsIndependentlyWithOneQueryEach() {
        List<MyRecordRow> rows = List.of(row(1L, "丢了一个蓝色水杯"), row(2L, "捡到一把雨伞"),
                row(3L, "丢了一张校园卡"));
        when(recordMapper.countByPublisher(any())).thenReturn(3L);
        when(recordMapper.selectMyRecords(any(), eq(0), eq(10))).thenReturn(rows);
        when(pairFactsPort.countsOf(any(), any())).thenReturn(Map.of(
                1L, new MyPairCounts(2, 2, 0, 0),
                2L, new MyPairCounts(1, 1, 0, 0)));
        when(pairFactsPort.cardsOf(any(), any())).thenReturn(Map.of(
                1L, List.of(PairCardFixtures.direct(1L, 101L),
                        PairCardFixtures.authorizableNotPublic(1L, 102L)),
                2L, List.of(PairCardFixtures.direct(2L, 103L))));

        PageResult<MyRecordListItemResponse> page = service.list(new MyRecordsQuery(), 1L);

        assertEquals(3, page.getTotal());
        assertEquals(3, page.getList().size());
        assertEquals(List.of(101L, 102L), page.getList().get(0).getPairs().stream()
                .map(card -> card.getPairId()).toList());
        assertEquals(1, page.getList().get(1).getPairs().size());
        assertEquals(103L, page.getList().get(1).getPairs().get(0).getPairId());
        assertTrue(page.getList().get(2).getPairs().isEmpty(), "无有效 pair 的记录给 []");
        assertEquals(0, page.getList().get(2).getRelatedCount());

        // N+1 防线：整页 id 一次传入，计数/卡片各一次
        verify(pairFactsPort).countsOf(eq(List.of(1L, 2L, 3L)), any());
        verify(pairFactsPort).cardsOf(eq(List.of(1L, 2L, 3L)), any());
        verify(recordMapper).selectMyRecords(any(), eq(0), eq(10));
    }

    @Test
    @DisplayName("空页：直接返回，不查 pair（避免空 IN 查询）")
    void emptyPageSkipsPairQueries() {
        when(recordMapper.countByPublisher(any())).thenReturn(0L);

        PageResult<MyRecordListItemResponse> page = service.list(new MyRecordsQuery(), 1L);

        assertEquals(0, page.getList().size());
        verify(pairFactsPort, never()).countsOf(any(), any());
        verify(pairFactsPort, never()).cardsOf(any(), any());
    }

    private static MyRecordRow row(long id, String title) {
        MyRecordRow row = new MyRecordRow();
        row.setId(id);
        row.setType(RecordType.LOST);
        row.setStatus(RecordStatus.PENDING);
        row.setTitle(title);
        row.setLocation("图书馆");
        row.setPairState("READY");
        row.setOccurredAt(LocalDateTime.of(2026, 10, 20, 15, 0));
        row.setCreatedAt(LocalDateTime.of(2026, 10, 20, 15, 30));
        row.setUpdatedAt(LocalDateTime.of(2026, 10, 20, 15, 30));
        return row;
    }
}
