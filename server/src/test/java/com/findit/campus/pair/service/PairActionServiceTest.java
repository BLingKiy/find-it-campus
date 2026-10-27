package com.findit.campus.pair.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.findit.campus.common.api.ErrorCode;
import com.findit.campus.common.error.BizException;
import com.findit.campus.pair.config.PairPolicyProperties;
import com.findit.campus.pair.domain.PairLookup;
import com.findit.campus.pair.domain.PairScore;
import com.findit.campus.pair.domain.PairStatuses;
import com.findit.campus.pair.mapper.PairScoreMapper;
import com.findit.campus.pair.web.dto.PairActionRequest;
import com.findit.campus.pair.web.dto.PairActionResult;
import com.findit.campus.record.domain.Record;
import com.findit.campus.record.domain.RecordStatus;
import com.findit.campus.record.domain.RecordType;

/**
 * {@code PATCH /api/pairs/{id}}：四态状态机、同 action 幂等、终态 409、MATCHED 由两端派生。
 */
@DisplayName("PairActionService：CONTACT / CONFIRM / IGNORE 状态机")
class PairActionServiceTest {

    private PairLookupService lookupService;
    private PairScoreMapper pairScoreMapper;
    private PairActionService service;

    @BeforeEach
    void setUp() {
        lookupService = mock(PairLookupService.class);
        pairScoreMapper = mock(PairScoreMapper.class);
        service = new PairActionService(lookupService, pairScoreMapper, new PairPolicyProperties());
    }

    @Test
    @DisplayName("NEW + CONTACT → CONTACTED；返回 data.matched=false、message=已联系")
    void contactMovesNewToContacted() {
        PairLookup lookup = lookup(PairStatuses.NEW);
        when(lookupService.load(101L)).thenReturn(lookup);
        PairScore updated = copy(lookup.pair());
        updated.setStatus(PairStatuses.CONTACTED);
        updated.setUpdatedAt(LocalDateTime.of(2026, 10, 27, 10, 0));
        when(pairScoreMapper.selectById(101L)).thenReturn(updated);

        PairActionResult result = service.act(101L, 1L, action("CONTACT"));

        verify(pairScoreMapper).markContacted(101L);
        assertEquals(PairStatuses.CONTACTED, result.data().getStatus());
        assertFalse(result.data().getMatched());
        assertEquals("已联系", result.message());
    }

    @Test
    @DisplayName("CONTACTED + CONTACT 幂等：不写库，updatedAt 不变")
    void contactIsIdempotent() {
        PairLookup lookup = lookup(PairStatuses.CONTACTED);
        lookup.pair().setUpdatedAt(LocalDateTime.of(2026, 10, 27, 9, 0));
        when(lookupService.load(101L)).thenReturn(lookup);
        when(pairScoreMapper.selectById(101L)).thenReturn(lookup.pair());

        PairActionResult result = service.act(101L, 1L, action("CONTACT"));

        verify(pairScoreMapper, never()).markContacted(anyLong());
        assertEquals(LocalDateTime.of(2026, 10, 27, 9, 0), result.data().getUpdatedAt());
        assertEquals("已联系", result.message());
    }

    @Test
    @DisplayName("IGNORED + CONTACT → 409/50003")
    void contactOnTerminalIsConflict() {
        when(lookupService.load(101L)).thenReturn(lookup(PairStatuses.IGNORED));

        assertEquals(ErrorCode.PAIR_STATE_CONFLICT,
                assertThrows(BizException.class, () -> service.act(101L, 1L, action("CONTACT"))).errorCode());
    }

    @Test
    @DisplayName("单端 CONFIRM → CONTACTED，只写自己拥有的端")
    void confirmOneSideWaitsForOther() {
        PairLookup lookup = lookup(PairStatuses.NEW);
        when(lookupService.load(101L)).thenReturn(lookup);
        PairScore updated = copy(lookup.pair());
        updated.setStatus(PairStatuses.CONTACTED);
        updated.setLostConfirmedAt(LocalDateTime.of(2026, 10, 27, 10, 0));
        when(pairScoreMapper.selectById(101L)).thenReturn(updated);

        PairActionResult result = service.act(101L, 1L, action("CONFIRM"));

        verify(pairScoreMapper).confirmOwned(eq(101L), any(), eq(true), eq(false));
        assertEquals(PairStatuses.CONTACTED, result.data().getStatus());
        assertTrue(result.data().getMyConfirmed());
        assertFalse(result.data().getOtherConfirmed());
        assertEquals("已确认，等待对方确认", result.message());
    }

    @Test
    @DisplayName("双端同主 + CONFIRM → 一次写两端并 MATCHED；记录状态不受影响（本类不碰 t_record）")
    void bothEndsConfirmBecomesMatched() {
        PairLookup lookup = lookup(PairStatuses.NEW);
        lookup.found().setPublisherId(1L);
        when(lookupService.load(101L)).thenReturn(lookup);
        PairScore updated = copy(lookup.pair());
        updated.setStatus(PairStatuses.MATCHED);
        updated.setLostConfirmedAt(LocalDateTime.of(2026, 10, 27, 10, 0));
        updated.setFoundConfirmedAt(LocalDateTime.of(2026, 10, 27, 10, 0));
        when(pairScoreMapper.selectById(101L)).thenReturn(updated);

        PairActionResult result = service.act(101L, 1L, action("CONFIRM"));

        verify(pairScoreMapper).confirmOwned(eq(101L), any(), eq(true), eq(true));
        assertTrue(result.data().getMatched());
        assertEquals("已完成双方确认", result.message());
    }

    @Test
    @DisplayName("MATCHED + CONFIRM 幂等：不刷新 confirmed_at")
    void confirmOnMatchedDoesNotWrite() {
        PairLookup lookup = lookup(PairStatuses.MATCHED);
        lookup.pair().setLostConfirmedAt(LocalDateTime.of(2026, 10, 27, 9, 0));
        lookup.pair().setFoundConfirmedAt(LocalDateTime.of(2026, 10, 27, 9, 5));
        when(lookupService.load(101L)).thenReturn(lookup);

        PairActionResult result = service.act(101L, 1L, action("CONFIRM"));

        verify(pairScoreMapper, never()).confirmOwned(anyLong(), any(), anyBoolean(), anyBoolean());
        assertEquals(LocalDateTime.of(2026, 10, 27, 9, 0), result.data().getLostConfirmedAt());
        assertEquals("已完成双方确认", result.message());
    }

    @Test
    @DisplayName("NEW / CONTACTED + IGNORE → IGNORED")
    void ignoreMovesToTerminal() {
        PairLookup lookup = lookup(PairStatuses.NEW);
        when(lookupService.load(101L)).thenReturn(lookup);
        PairScore updated = copy(lookup.pair());
        updated.setStatus(PairStatuses.IGNORED);
        when(pairScoreMapper.selectById(101L)).thenReturn(updated);

        assertEquals("已忽略", service.act(101L, 1L, action("IGNORE")).message());
        verify(pairScoreMapper).markIgnored(101L);
    }

    @Test
    @DisplayName("IGNORED + IGNORE 幂等：不写库")
    void ignoreOnIgnoredIsIdempotent() {
        PairLookup ignored = lookup(PairStatuses.IGNORED);
        when(lookupService.load(101L)).thenReturn(ignored);
        when(pairScoreMapper.selectById(101L)).thenReturn(ignored.pair());

        assertEquals("已忽略", service.act(101L, 1L, action("IGNORE")).message());
        verify(pairScoreMapper, never()).markIgnored(anyLong());
    }

    @Test
    @DisplayName("MATCHED + IGNORE → 409/50003")
    void ignoreOnMatchedIsConflict() {
        when(lookupService.load(101L)).thenReturn(lookup(PairStatuses.MATCHED));

        assertEquals(ErrorCode.PAIR_STATE_CONFLICT,
                assertThrows(BizException.class, () -> service.act(101L, 1L, action("IGNORE"))).errorCode());
    }

    @Test
    @DisplayName("请求体带 status → 400/10001（不能直传 MATCHED）")
    void statusFieldIsRejected() {
        when(lookupService.load(101L)).thenReturn(lookup(PairStatuses.NEW));
        PairActionRequest request = action("CONFIRM");
        request.putUnknown("status", "MATCHED");

        BizException ex = assertThrows(BizException.class, () -> service.act(101L, 1L, request));

        assertEquals(ErrorCode.VALIDATION_FAILED, ex.errorCode());
        assertTrue(ex.data().toString().contains("status"));
    }

    @Test
    @DisplayName("0.70 的 pair 任何 action 都是 403/50002（档位检查先于 action 执行）")
    void lowScoreBlocksAllActions() {
        PairLookup lookup = lookup(PairStatuses.NEW);
        lookup.pair().setScore(new BigDecimal("0.70"));
        when(lookupService.load(101L)).thenReturn(lookup);

        assertEquals(ErrorCode.CONTACT_TIER_INSUFFICIENT,
                assertThrows(BizException.class, () -> service.act(101L, 1L, action("CONTACT"))).errorCode());
    }

    @Test
    @DisplayName("CLOSED 记录的 pair → 403/50005")
    void closedRecordIsInvalid() {
        PairLookup lookup = lookup(PairStatuses.NEW);
        lookup.found().setStatus(RecordStatus.CLOSED);
        when(lookupService.load(101L)).thenReturn(lookup);

        assertEquals(ErrorCode.PAIR_INVALID,
                assertThrows(BizException.class, () -> service.act(101L, 1L, action("CONTACT"))).errorCode());
    }

    @Test
    @DisplayName("非参与方 → 403/10007")
    void nonParticipantIsForbidden() {
        when(lookupService.load(101L)).thenReturn(lookup(PairStatuses.NEW));

        assertEquals(ErrorCode.FORBIDDEN,
                assertThrows(BizException.class, () -> service.act(101L, 99L, action("CONTACT"))).errorCode());
    }

    private static PairActionRequest action(String action) {
        PairActionRequest request = new PairActionRequest();
        request.setAction(action);
        return request;
    }

    private static PairScore copy(PairScore source) {
        PairScore copy = new PairScore();
        copy.setId(source.getId());
        copy.setLostId(source.getLostId());
        copy.setFoundId(source.getFoundId());
        copy.setScore(source.getScore());
        copy.setStatus(source.getStatus());
        copy.setUpdatedAt(source.getUpdatedAt());
        copy.setLostConfirmedAt(source.getLostConfirmedAt());
        copy.setFoundConfirmedAt(source.getFoundConfirmedAt());
        return copy;
    }

    private static PairLookup lookup(String status) {
        PairScore pair = new PairScore();
        pair.setId(101L);
        pair.setLostId(11L);
        pair.setFoundId(12L);
        pair.setScore(new BigDecimal("0.92"));
        pair.setStatus(status);
        pair.setUpdatedAt(LocalDateTime.of(2026, 10, 27, 9, 0));

        Record lost = new Record();
        lost.setId(11L);
        lost.setType(RecordType.LOST);
        lost.setPublisherId(1L);
        lost.setStatus(RecordStatus.PENDING);
        lost.setTitle("丢失蓝色水杯");

        Record found = new Record();
        found.setId(12L);
        found.setType(RecordType.FOUND);
        found.setPublisherId(2L);
        found.setStatus(RecordStatus.PENDING);
        found.setTitle("捡到蓝色水杯");
        found.setContact("13800000002");

        return new PairLookup(pair, lost, found);
    }
}
