package com.findit.campus.pair.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.findit.campus.common.api.ErrorCode;
import com.findit.campus.common.error.BizException;
import com.findit.campus.notification.domain.Notification;
import com.findit.campus.notification.mapper.NotificationMapper;
import com.findit.campus.pair.config.PairPolicyProperties;
import com.findit.campus.pair.domain.PairLookup;
import com.findit.campus.pair.domain.PairScore;
import com.findit.campus.pair.domain.PairStatuses;
import com.findit.campus.pair.mapper.PairScoreMapper;
import com.findit.campus.pair.web.dto.ContactUnlockRequest;
import com.findit.campus.pair.web.dto.ContactUnlockResponse;
import com.findit.campus.record.domain.Record;
import com.findit.campus.record.domain.RecordStatus;
import com.findit.campus.record.domain.RecordType;

/**
 * {@code POST /api/pairs/{id}/contact-unlock}：逐端授权、撤回清附言、附言打码与长度边界。
 *
 * <p>「A 授权不影响 B」在服务层表现为：只调用指向当前 pair 的 publish/revoke 方法，
 * 且通知接收人取「另一端 publisher」，不遍历任何其它 pair。
 */
@DisplayName("PairUnlockService：PUBLISH / REVOKE / 附言打码 / 逐端隔离")
class PairUnlockServiceTest {

    private PairLookupService lookupService;
    private PairScoreMapper pairScoreMapper;
    private NotificationMapper notificationMapper;
    private PairUnlockService service;

    @BeforeEach
    void setUp() {
        lookupService = mock(PairLookupService.class);
        pairScoreMapper = mock(PairScoreMapper.class);
        notificationMapper = mock(NotificationMapper.class);
        service = new PairUnlockService(lookupService, pairScoreMapper, notificationMapper,
                new PairPolicyProperties());
    }

    @Test
    @DisplayName("PUBLISH：附言中的手机号落库前已打码，给对端写 PAIR_UNLOCK 通知")
    void publishMasksNoteAndNotifiesCounterpart() {
        PairLookup lookup = lookup("0.80", RecordStatus.PENDING, RecordStatus.PENDING);
        when(lookupService.load(101L)).thenReturn(lookup);
        PairScore refreshed = lookup.pair();
        refreshed.setLostUnlockedAt(LocalDateTime.of(2026, 10, 27, 10, 0));
        refreshed.setLostUnlockNote("联系我 [手机号已隐藏]");
        when(pairScoreMapper.selectById(101L)).thenReturn(refreshed);

        ContactUnlockRequest request = new ContactUnlockRequest();
        request.setNote("联系我 13800000002");
        ContactUnlockResponse response = service.unlock(101L, 1L, request);

        ArgumentCaptor<String> note = ArgumentCaptor.forClass(String.class);
        verify(pairScoreMapper).publishLost(eq(101L), any(), note.capture());
        assertTrue(note.getValue().contains("[手机号已隐藏]"));
        assertFalse(note.getValue().contains("13800000002"));
        verify(notificationMapper).insertIfAbsent(any(Notification.class));
        assertEquals("LOST", response.getSide());
        assertTrue(response.getPublic());
        assertEquals("联系我 [手机号已隐藏]", response.getNote());
    }

    @Test
    @DisplayName("REVOKE：清空本端授权与附言；不检查分数、不发通知")
    void revokeClearsNoteAndSkipsScoreCheck() {
        PairLookup lookup = lookup("0.70", RecordStatus.PENDING, RecordStatus.PENDING);
        lookup.pair().setLostUnlockedAt(LocalDateTime.of(2026, 10, 27, 10, 0));
        lookup.pair().setLostUnlockNote("周六见");
        when(lookupService.load(101L)).thenReturn(lookup);
        PairScore refreshed = lookup.pair();
        refreshed.setLostUnlockedAt(null);
        refreshed.setLostUnlockNote(null);
        when(pairScoreMapper.selectById(101L)).thenReturn(refreshed);

        ContactUnlockRequest request = new ContactUnlockRequest();
        request.setAction("REVOKE");
        ContactUnlockResponse response = service.unlock(101L, 1L, request);

        verify(pairScoreMapper).revokeLost(101L);
        verify(pairScoreMapper, never()).publishLost(anyLong(), any(), any());
        verifyNoInteractions(notificationMapper);
        assertFalse(response.getPublic());
        assertNull(response.getUnlockedAt());
        assertNull(response.getNote());
    }

    @Test
    @DisplayName("附言 201 字 → 400/10001，零写入")
    void noteTooLongIsRejected() {
        ContactUnlockRequest request = new ContactUnlockRequest();
        request.setNote("a".repeat(201));

        BizException ex = assertThrows(BizException.class, () -> service.unlock(101L, 1L, request));

        assertEquals(ErrorCode.VALIDATION_FAILED, ex.errorCode());
        verifyNoInteractions(pairScoreMapper, lookupService, notificationMapper);
    }

    @Test
    @DisplayName("PUBLISH 且 0.70 → 403/50002；REVOKE 同分数 → 200（撤回同意不受分数限制）")
    void publishNeedsTierButRevokeDoesNot() {
        when(lookupService.load(101L)).thenReturn(lookup("0.70", RecordStatus.PENDING, RecordStatus.PENDING));

        ContactUnlockRequest publish = new ContactUnlockRequest();
        assertEquals(ErrorCode.CONTACT_TIER_INSUFFICIENT,
                assertThrows(BizException.class, () -> service.unlock(101L, 1L, publish)).errorCode());

        when(pairScoreMapper.selectById(101L)).thenReturn(lookup("0.70", RecordStatus.PENDING, RecordStatus.PENDING).pair());
        ContactUnlockRequest revoke = new ContactUnlockRequest();
        revoke.setAction("REVOKE");
        service.unlock(101L, 1L, revoke);
        verify(pairScoreMapper).revokeLost(101L);
    }

    @Test
    @DisplayName("CLOSED 记录的 pair：PUBLISH / REVOKE 都 403/50005")
    void closedRecordRejectsBothActions() {
        when(lookupService.load(101L)).thenReturn(lookup("0.95", RecordStatus.PENDING, RecordStatus.CLOSED));

        assertEquals(ErrorCode.PAIR_INVALID, assertThrows(BizException.class,
                () -> service.unlock(101L, 1L, new ContactUnlockRequest())).errorCode());
        ContactUnlockRequest revoke = new ContactUnlockRequest();
        revoke.setAction("REVOKE");
        assertEquals(ErrorCode.PAIR_INVALID,
                assertThrows(BizException.class, () -> service.unlock(101L, 1L, revoke)).errorCode());
    }

    @Test
    @DisplayName("同一用户拥有两端：side 固定 LOST、只写 LOST 端、不给自己发通知")
    void bothEndsOwnedPublishesLostOnly() {
        PairLookup lookup = lookup("0.80", RecordStatus.PENDING, RecordStatus.PENDING);
        lookup.found().setPublisherId(1L);
        when(lookupService.load(101L)).thenReturn(lookup);
        PairScore refreshed = lookup.pair();
        refreshed.setLostUnlockedAt(LocalDateTime.of(2026, 10, 27, 10, 0));
        when(pairScoreMapper.selectById(101L)).thenReturn(refreshed);

        ContactUnlockResponse response = service.unlock(101L, 1L, new ContactUnlockRequest());

        assertEquals("LOST", response.getSide());
        verify(pairScoreMapper).publishLost(eq(101L), any(), any());
        verify(pairScoreMapper, never()).publishFound(anyLong(), any(), any());
        verifyNoInteractions(notificationMapper);
    }

    @Test
    @DisplayName("非参与方：403/10007，不写任何 pair 列")
    void nonParticipantCannotUnlock() {
        when(lookupService.load(101L)).thenReturn(lookup("0.95", RecordStatus.PENDING, RecordStatus.PENDING));

        assertEquals(ErrorCode.FORBIDDEN,
                assertThrows(BizException.class, () -> service.unlock(101L, 99L, new ContactUnlockRequest()))
                        .errorCode());
        verifyNoInteractions(pairScoreMapper, notificationMapper);
    }

    private static PairLookup lookup(String score, RecordStatus lostStatus, RecordStatus foundStatus) {
        PairScore pair = new PairScore();
        pair.setId(101L);
        pair.setLostId(11L);
        pair.setFoundId(12L);
        pair.setScore(new BigDecimal(score));
        pair.setStatus(PairStatuses.NEW);

        Record lost = new Record();
        lost.setId(11L);
        lost.setType(RecordType.LOST);
        lost.setPublisherId(1L);
        lost.setStatus(lostStatus);
        lost.setReviewState(com.findit.campus.record.domain.RecordReviewState.APPROVED);
        lost.setTitle("丢失蓝色水杯");

        Record found = new Record();
        found.setId(12L);
        found.setType(RecordType.FOUND);
        found.setPublisherId(2L);
        found.setStatus(foundStatus);
        found.setReviewState(com.findit.campus.record.domain.RecordReviewState.APPROVED);
        found.setTitle("捡到蓝色水杯");
        found.setContact("13800000002");

        return new PairLookup(pair, lost, found);
    }
}
