package com.findit.campus.record.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import com.findit.campus.auth.domain.SessionUser;
import com.findit.campus.category.service.CategoryService;
import com.findit.campus.common.api.ErrorCode;
import com.findit.campus.common.error.BizException;
import com.findit.campus.pair.config.PairPolicyProperties;
import com.findit.campus.record.domain.Record;
import com.findit.campus.record.domain.RecordStatus;
import com.findit.campus.record.domain.RecordType;
import com.findit.campus.record.mapper.RecordMapper;
import com.findit.campus.record.web.dto.RecordCreateRequest;
import com.findit.campus.record.web.dto.RecordDetailResponse;
import com.findit.campus.record.web.dto.StatusUpdateRequest;
import com.findit.campus.record.web.dto.StatusUpdateResponse;
import com.findit.campus.support.PairCardFixtures;

/**
 * 所有权 + 终态可见性 + 状态写入路径的 service 级断言（不依赖 MySQL）。
 *
 * <p>用 mock 的 {@link RecordMapper} 而不是 MockMvc，是因为要验的恰恰是<b>不该发生的写</b>：
 * 非所有者 PATCH/DELETE 之后 {@code updateContent}/{@code markDeleted} 一次都不能被调用。
 * 这类断言只有 verify(never()) 能表达，HTTP 状态码看不出来。
 */
@DisplayName("RecordService：所有权 403 / 终态 404 / 完成前置 409")
class RecordServiceAccessTest {

    private static final long OWNER = 1L;
    private static final long OTHER = 2L;
    private static final long RECORD_ID = 9L;

    private RecordMapper recordMapper;
    private CategoryService categoryService;
    private PairFactsPort pairFactsPort;
    private ApplicationEventPublisher eventPublisher;
    private RecordService recordService;

    @BeforeEach
    void setUp() {
        recordMapper = mock(RecordMapper.class);
        categoryService = mock(CategoryService.class);
        pairFactsPort = mock(PairFactsPort.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        recordService = new RecordService(recordMapper, categoryService, pairFactsPort,
                new PairPolicyProperties(), eventPublisher);
    }

    @Test
    @DisplayName("非所有者 PUT → 403/10007，且不改内容（updateContent 零调用）")
    void nonOwnerUpdateIsForbiddenAndWritesNothing() {
        when(recordMapper.selectById(RECORD_ID)).thenReturn(record(RecordStatus.PENDING, OWNER, null));

        BizException ex = assertThrows(BizException.class,
                () -> recordService.update(RECORD_ID, new RecordCreateRequest(), OTHER));

        assertEquals(ErrorCode.FORBIDDEN, ex.errorCode());
        assertEquals(403, ex.errorCode().httpStatus().value());
        verify(recordMapper, never()).updateContent(any());
    }

    @Test
    @DisplayName("P2-1 并发：读到的 PENDING 在 UPDATE 前被 PATCH 关成 CLOSED → 0 行 + 20003，且不触发重算")
    void updateRacingWithTerminalPatchReturns20003() {
        when(recordMapper.selectById(RECORD_ID)).thenReturn(record(RecordStatus.PENDING, OWNER, null));
        when(recordMapper.updateContent(any())).thenReturn(0);
        // 锁定读看到并发 PATCH 已提交的终态（普通 SELECT 在 RR 下可能仍是旧 PENDING）
        when(recordMapper.selectStatusByIdForUpdate(RECORD_ID)).thenReturn("CLOSED");

        BizException ex = assertThrows(BizException.class,
                () -> recordService.update(RECORD_ID, updateRequest(), OWNER));

        assertEquals(ErrorCode.RECORD_OPERATION_NOT_ALLOWED, ex.errorCode());
        assertEquals(409, ex.errorCode().httpStatus().value());
        assertTrue(ex.getMessage().contains("CLOSED"), "文案应指出当前终态，便于排障");
        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("P2-1 并发：UPDATE 0 行且回读不到记录（已删除）→ 20001，不是 20003")
    void updateZeroRowsAndRecordGoneReturns20001() {
        when(recordMapper.selectById(RECORD_ID)).thenReturn(record(RecordStatus.PENDING, OWNER, null));
        when(recordMapper.updateContent(any())).thenReturn(0);
        when(recordMapper.selectStatusByIdForUpdate(RECORD_ID)).thenReturn(null);

        BizException ex = assertThrows(BizException.class,
                () -> recordService.update(RECORD_ID, updateRequest(), OWNER));

        assertEquals(ErrorCode.RECORD_NOT_FOUND, ex.errorCode());
        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("P2-1 正常路径：条件更新命中 1 行 → 200 + 触发重算（条件不改变原行为）")
    void updateSuccessStillPublishesScoringEvent() {
        Record pending = record(RecordStatus.PENDING, OWNER, null);
        when(recordMapper.selectById(RECORD_ID)).thenReturn(pending, pending);
        when(recordMapper.updateContent(any())).thenReturn(1);
        when(pairFactsPort.cardsOf(any(), any())).thenReturn(Map.of());

        RecordDetailResponse response = recordService.update(RECORD_ID, updateRequest(), OWNER);

        assertEquals(RECORD_ID, response.getId());
        verify(recordMapper).updateContent(any());
        verify(eventPublisher).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("非所有者 DELETE → 403/10007，deleted_at 不动（markDeleted 零调用）")
    void nonOwnerDeleteIsForbiddenAndWritesNothing() {
        when(recordMapper.selectById(RECORD_ID)).thenReturn(record(RecordStatus.PENDING, OWNER, null));

        BizException ex = assertThrows(BizException.class, () -> recordService.delete(RECORD_ID, OTHER));

        assertEquals(ErrorCode.FORBIDDEN, ex.errorCode());
        verify(recordMapper, never()).markDeleted(anyLong(), any());
    }

    @Test
    @DisplayName("非所有者 PATCH status → 403/10007，markCompleted/close 零调用")
    void nonOwnerStatusPatchIsForbiddenAndWritesNothing() {
        when(recordMapper.selectById(RECORD_ID)).thenReturn(record(RecordStatus.PENDING, OWNER, null));

        BizException ex = assertThrows(BizException.class,
                () -> recordService.updateStatus(RECORD_ID, statusRequest("CLOSED"), OTHER));

        assertEquals(ErrorCode.FORBIDDEN, ex.errorCode());
        verify(recordMapper, never()).markCompleted(anyLong(), any());
        verify(recordMapper, never()).close(anyLong(), any());
    }

    @Test
    @DisplayName("所有者标记完成但无有效 MATCHED → 409/20005，且零写入（三个时间/状态列都不变）")
    void completeWithoutMatchedPairIs409AndWritesNothing() {
        when(recordMapper.selectById(RECORD_ID)).thenReturn(record(RecordStatus.PENDING, OWNER, null));
        when(pairFactsPort.countValidMatched(eq(RECORD_ID), any())).thenReturn(0);

        BizException ex = assertThrows(BizException.class,
                () -> recordService.updateStatus(RECORD_ID, statusRequest("COMPLETED"), OWNER));

        assertEquals(ErrorCode.PAIR_REQUIRED_FOR_COMPLETE, ex.errorCode());
        assertEquals(409, ex.errorCode().httpStatus().value());
        assertEquals(0, ex.data().get("matchedPairCount"));
        verify(recordMapper, never()).markCompleted(anyLong(), any());
        verify(recordMapper, never()).close(anyLong(), any());
    }

    @Test
    @DisplayName("所有者标记完成且有 MATCHED → 200，markCompleted 恰好一次")
    void completeWithMatchedPairWritesOnce() {
        Record pending = record(RecordStatus.PENDING, OWNER, null);
        Record completed = record(RecordStatus.COMPLETED, OWNER, LocalDateTime.now());
        when(recordMapper.selectById(RECORD_ID)).thenReturn(pending, completed);
        when(pairFactsPort.countValidMatched(eq(RECORD_ID), any())).thenReturn(1);
        when(recordMapper.markCompleted(eq(RECORD_ID), any())).thenReturn(1);

        StatusUpdateResponse response = recordService.updateStatus(RECORD_ID, statusRequest("COMPLETED"), OWNER);

        assertEquals("COMPLETED", response.getStatus());
        assertTrue(response.getHasMatchedPair());
        verify(recordMapper).markCompleted(eq(RECORD_ID), any());
        verify(recordMapper, never()).close(anyLong(), any());
        // 不触发任何 pair 变更（契约 §4.5：完成是本人动作）
        verify(pairFactsPort).countValidMatched(eq(RECORD_ID), any());
    }

    @Test
    @DisplayName("所有者关闭 PENDING：只调 close，不碰 completed_at 的完成路径")
    void closeWritesOnlyClose() {
        Record pending = record(RecordStatus.PENDING, OWNER, null);
        Record closed = record(RecordStatus.CLOSED, OWNER, null);
        when(recordMapper.selectById(RECORD_ID)).thenReturn(pending, closed);
        when(recordMapper.close(eq(RECORD_ID), any())).thenReturn(1);

        StatusUpdateResponse response = recordService.updateStatus(RECORD_ID, statusRequest("CLOSED"), OWNER);

        assertEquals("CLOSED", response.getStatus());
        assertNull(response.getCompletedAt());
        verify(recordMapper).close(eq(RECORD_ID), any());
        verify(recordMapper, never()).markCompleted(anyLong(), any());
    }

    @Test
    @DisplayName("详情：CLOSED 对所有者/非所有者都 404，且不查 pair（不可见即不暴露）")
    void closedDetailIs404AndSkipsPairFacts() {
        when(recordMapper.selectById(RECORD_ID)).thenReturn(record(RecordStatus.CLOSED, OWNER, null));

        for (Long viewer : new Long[]{OWNER, OTHER, null}) {
            BizException ex = assertThrows(BizException.class, () -> recordService.detail(RECORD_ID, viewer));
            assertEquals(ErrorCode.RECORD_NOT_FOUND, ex.errorCode());
        }
        verifyNoInteractions(pairFactsPort);
    }

    @Test
    @DisplayName("详情：COMPLETED 所有者 404（A9 字面），不查 pair")
    void completedDetailOwnerIs404() {
        Record completed = record(RecordStatus.COMPLETED, OWNER, LocalDateTime.now().minusDays(3));
        when(recordMapper.selectById(RECORD_ID)).thenReturn(completed);

        BizException ex = assertThrows(BizException.class, () -> recordService.detail(RECORD_ID, OWNER));

        assertEquals(ErrorCode.RECORD_NOT_FOUND, ex.errorCode());
        verifyNoInteractions(pairFactsPort);
    }

    @Test
    @DisplayName("详情：COMPLETED 合格对端 → 200 遮蔽视图（contact=null，completedAt=null，isMine=false）")
    void completedDetailQualifiedPeerIsMasked() {
        Record completed = record(RecordStatus.COMPLETED, OWNER, LocalDateTime.now().minusDays(3));
        completed.setDescription("昨天 14:30 联系 13812345678");
        completed.setLocation("第三教学楼201");
        completed.setContact("13812345678");
        completed.setPublisherName("李同学");
        when(recordMapper.selectById(RECORD_ID)).thenReturn(completed);
        when(pairFactsPort.viewerHasQualifyingPair(eq(RECORD_ID), eq(OTHER), any())).thenReturn(true);

        RecordDetailResponse response = recordService.detail(RECORD_ID, OTHER);

        assertNull(response.getContact());
        assertNull(response.getCompletedAt());
        assertEquals(Boolean.FALSE, response.getIsMine());
        assertEquals("第三教学楼…", response.getLocation());
        assertEquals("李**", response.getPublisherName());
        assertTrue(response.getDescription().contains("[手机号已隐藏]"));
    }

    @Test
    @DisplayName("详情：COMPLETED 唯一关联 pair 为 IGNORED（合格对端=false）→ 404，与没有 pair 同码")
    void completedDetailIgnoredPairIs404() {
        Record completed = record(RecordStatus.COMPLETED, OWNER, LocalDateTime.now().minusDays(3));
        when(recordMapper.selectById(RECORD_ID)).thenReturn(completed);
        when(pairFactsPort.viewerHasQualifyingPair(eq(RECORD_ID), eq(OTHER), any())).thenReturn(false);

        BizException ex = assertThrows(BizException.class, () -> recordService.detail(RECORD_ID, OTHER));
        assertEquals(ErrorCode.RECORD_NOT_FOUND, ex.errorCode());
    }

    @Test
    @DisplayName("详情：COMPLETED 已到期 → 任何身份 404（合格对端也不放行）")
    void completedDetailExpiredIs404() {
        Record expired = record(RecordStatus.COMPLETED, OWNER, LocalDateTime.now().minusDays(31));
        when(recordMapper.selectById(RECORD_ID)).thenReturn(expired);
        when(pairFactsPort.viewerHasQualifyingPair(eq(RECORD_ID), eq(OTHER), any())).thenReturn(true);

        BizException ex = assertThrows(BizException.class, () -> recordService.detail(RECORD_ID, OTHER));
        assertEquals(ErrorCode.RECORD_NOT_FOUND, ex.errorCode());
    }

    @Test
    @DisplayName("详情：PENDING 非所有者 200 遮蔽（pairs 为 null = 字段省略）；所有者 200 明文 + 计数 + 卡片")
    void pendingDetailIdentityDimension() {
        Record pending = record(RecordStatus.PENDING, OWNER, null);
        pending.setContact("13812345678");
        pending.setPublisherName("李同学");
        when(recordMapper.selectById(RECORD_ID)).thenReturn(pending);
        when(pairFactsPort.countValidMatched(eq(RECORD_ID), any())).thenReturn(1);
        when(pairFactsPort.countLowConfidence(eq(RECORD_ID), any())).thenReturn(2);
        when(pairFactsPort.cardsOf(any(), any())).thenReturn(
                Map.of(RECORD_ID, List.of(
                        PairCardFixtures.direct(RECORD_ID, 101L),
                        PairCardFixtures.authorizableNotPublic(RECORD_ID, 102L))));

        RecordDetailResponse masked = recordService.detail(RECORD_ID, OTHER);
        assertNull(masked.getContact());
        assertEquals(Boolean.FALSE, masked.getIsMine());
        // 非所有者：字段省略（null），不是空数组；前端用 isMine 判断
        assertNull(masked.getPairs());

        RecordDetailResponse owner = recordService.detail(RECORD_ID, OWNER);
        assertEquals("13812345678", owner.getContact());
        assertEquals(Boolean.TRUE, owner.getIsMine());
        assertTrue(owner.getAllowedStatuses().contains("COMPLETED"));
        assertEquals(2, owner.getLowConfidenceCount());
        assertNotNull(owner.getPairs());
        assertEquals(2, owner.getPairs().size());
        assertEquals(101L, owner.getPairs().get(0).getPairId());
        assertEquals("DIRECT", owner.getPairs().get(0).getTier());
        assertEquals("AUTHORIZABLE", owner.getPairs().get(1).getTier());
    }

    @Test
    @DisplayName("详情：COMPLETED 合格对端的遮蔽视图 pairs 为 null（不是空数组）")
    void completedPeerDetailOmitsPairs() {
        Record completed = record(RecordStatus.COMPLETED, OWNER, LocalDateTime.now().minusDays(3));
        when(recordMapper.selectById(RECORD_ID)).thenReturn(completed);
        when(pairFactsPort.viewerHasQualifyingPair(eq(RECORD_ID), eq(OTHER), any())).thenReturn(true);

        RecordDetailResponse response = recordService.detail(RECORD_ID, OTHER);

        assertNull(response.getPairs());
    }

    @Test
    @DisplayName("提交：publisher_id 取 session，不接受请求体里的身份；publisherName 缺省用 displayName")
    void createUsesSessionIdentity() {
        when(recordMapper.insert(any(Record.class))).thenAnswer(invocation -> {
            Record record = invocation.getArgument(0);
            record.setId(77L);
            return 1;
        });
        Record persisted = record(RecordStatus.PENDING, OWNER, null);
        persisted.setId(77L);
        persisted.setPublisherName("演示甲");
        when(recordMapper.selectById(77L)).thenReturn(persisted);

        RecordCreateRequest request = new RecordCreateRequest();
        request.setType("LOST");
        request.setTitle("丢了一个背包");
        request.setDescription("昨天下午在图书馆丢的");
        request.setLocation("图书馆三楼");
        request.setOccurredAt("2026-10-20 15:00:00");
        request.setContact("13812345678");

        RecordDetailResponse response = recordService.create(request, new SessionUser(OWNER, "demo01", "演示甲"));

        assertEquals(77L, response.getId());
        assertEquals(Boolean.TRUE, response.getIsMine());
        // 契约 §4.3：发布成功响应 pairs=[]（刚建的记录不可能已有达阈值的 pair）
        assertNotNull(response.getPairs());
        assertTrue(response.getPairs().isEmpty());
        verify(recordMapper).insert(any(Record.class));
    }

    private static StatusUpdateRequest statusRequest(String status) {
        StatusUpdateRequest request = new StatusUpdateRequest();
        request.setStatus(status);
        return request;
    }

    private static RecordCreateRequest updateRequest() {
        RecordCreateRequest request = new RecordCreateRequest();
        request.setType("LOST");
        request.setTitle("丢了一个背包");
        request.setDescription("昨天下午在图书馆丢的");
        request.setCategoryId(3L);
        request.setLocation("图书馆三楼");
        request.setOccurredAt("2026-10-20 15:00:00");
        request.setContact("13812345678");
        request.setPublisherName("李同学");
        return request;
    }

    private static Record record(RecordStatus status, Long publisherId, LocalDateTime completedAt) {
        Record record = new Record();
        record.setId(RECORD_ID);
        record.setType(RecordType.LOST);
        record.setStatus(status);
        record.setPublisherId(publisherId);
        record.setCompletedAt(completedAt);
        record.setTitle("丢了一个背包");
        record.setDescription("描述");
        record.setLocation("图书馆");
        record.setContact("13812345678");
        record.setPublisherName("李同学");
        record.setPairState("READY");
        return record;
    }
}
