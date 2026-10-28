package com.findit.campus.pair.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.findit.campus.common.api.ErrorCode;
import com.findit.campus.common.error.BizException;
import com.findit.campus.pair.config.PairPolicyProperties;
import com.findit.campus.pair.domain.AccessResult;
import com.findit.campus.pair.domain.ContactTier;
import com.findit.campus.pair.domain.PairLookup;
import com.findit.campus.pair.domain.PairScore;
import com.findit.campus.pair.domain.PairStatuses;
import com.findit.campus.pair.web.dto.ContactView;
import com.findit.campus.record.domain.Record;
import com.findit.campus.record.domain.RecordStatus;
import com.findit.campus.record.domain.RecordType;

/**
 * {@code GET /api/pairs/{id}/contact}：三档 × 授权前后 × 撤回前后的服务层判定。
 *
 * <p>审计断言是本类的重点：每一次调用（包括 200 但 available=false）都必须落一条 result，
 * 否则「谁看过号码」在未授权分支上就是盲区。
 */
@DisplayName("PairContactService：三档 / 逐端授权 / 审计结果")
class PairContactServiceTest {

    private PairLookupService lookupService;
    private AccessLogService accessLogService;
    private PairContactService contactService;

    private static final AuditMeta META = new AuditMeta("req-1", "127.0.0.1", "test-agent");

    @BeforeEach
    void setUp() {
        lookupService = mock(PairLookupService.class);
        accessLogService = mock(AccessLogService.class);
        contactService = new PairContactService(lookupService, new PairPolicyProperties(), accessLogService);
    }

    @Test
    @DisplayName("0.96 直看：LOST 端拿到 FOUND 号码，审计 GRANTED，note 不因未授权而泄露")
    void directTierReturnsContact() {
        when(lookupService.load(101L)).thenReturn(lookup("0.96", RecordStatus.PENDING, RecordStatus.PENDING));

        ContactView view = contactService.view(101L, 1L, META);

        assertEquals(101L, view.getPairId());
        assertEquals(ContactTier.DIRECT.name(), view.getTier());
        assertEquals("LOST", view.getViewerSide());
        assertEquals("FOUND", view.getTargetSide());
        assertTrue(view.getAvailable());
        assertEquals("13800000002", view.getContact());
        assertNull(view.getReason());
        assertEquals("匹配度较高，可直接查看", view.getMessage());
        assertNull(view.getNote());
        assertAudit(AccessResult.GRANTED);
    }

    @Test
    @DisplayName("0.80 且对方未公开：200 + available=false + NOT_PUBLIC，不是 403")
    void authorizableButNotPublicIsSuccessBody() {
        when(lookupService.load(101L)).thenReturn(lookup("0.80", RecordStatus.PENDING, RecordStatus.PENDING));

        ContactView view = contactService.view(101L, 1L, META);

        assertFalse(view.getAvailable());
        assertNull(view.getContact());
        assertEquals("NOT_PUBLIC", view.getReason());
        assertEquals("对方尚未公开", view.getMessage());
        assertEquals(ContactTier.AUTHORIZABLE.name(), view.getTier());
        assertAudit(AccessResult.DENIED_NOT_PUBLIC);
    }

    @Test
    @DisplayName("对方授权后：200 + 号码 + 附言 + unlockedAt，审计 GRANTED")
    void authorizableAndPublicReturnsContactAndNote() {
        PairLookup lookup = lookup("0.80", RecordStatus.PENDING, RecordStatus.PENDING);
        lookup.found().setContact("13800000002");
        lookup.pair().setFoundUnlockedAt(LocalDateTime.of(2026, 10, 27, 10, 0));
        lookup.pair().setFoundUnlockNote("周六下午在图书馆门口等");
        when(lookupService.load(101L)).thenReturn(lookup);

        ContactView view = contactService.view(101L, 1L, META);

        assertTrue(view.getAvailable());
        assertEquals("13800000002", view.getContact());
        assertEquals("周六下午在图书馆门口等", view.getNote());
        assertEquals(LocalDateTime.of(2026, 10, 27, 10, 0), view.getUnlockedAt());
        assertAudit(AccessResult.GRANTED);
    }

    @Test
    @DisplayName("直看档但对方没填号码：available=false + NOT_PROVIDED，审计 DENIED_NOT_PROVIDED")
    void missingContactIsNotProvided() {
        PairLookup lookup = lookup("0.95", RecordStatus.PENDING, RecordStatus.PENDING);
        lookup.found().setContact("  ");
        when(lookupService.load(101L)).thenReturn(lookup);

        ContactView view = contactService.view(101L, 1L, META);

        assertFalse(view.getAvailable());
        assertNull(view.getContact());
        assertEquals("NOT_PROVIDED", view.getReason());
        assertEquals("对方未填写联系方式", view.getMessage());
        assertAudit(AccessResult.DENIED_NOT_PROVIDED);
    }

    @Test
    @DisplayName("<0.80：403/50002，审计 DENIED_LOW_SCORE")
    void lowScoreIsForbidden() {
        when(lookupService.load(101L)).thenReturn(lookup("0.70", RecordStatus.PENDING, RecordStatus.PENDING));

        BizException ex = assertThrows(BizException.class, () -> contactService.view(101L, 1L, META));

        assertEquals(ErrorCode.CONTACT_TIER_INSUFFICIENT, ex.errorCode());
        assertAudit(AccessResult.DENIED_LOW_SCORE);
    }

    @Test
    @DisplayName("任一端 CLOSED：403/50005（不是 404），审计 DENIED_PAIR_INVALID")
    void closedRecordInvalidatesPair() {
        when(lookupService.load(101L)).thenReturn(lookup("0.95", RecordStatus.PENDING, RecordStatus.CLOSED));

        BizException ex = assertThrows(BizException.class, () -> contactService.view(101L, 1L, META));

        assertEquals(ErrorCode.PAIR_INVALID, ex.errorCode());
        assertAudit(AccessResult.DENIED_PAIR_INVALID);
    }

    @Test
    @DisplayName("COMPLETED 到期：403/50005；未到期仍按三档（0.92 直看 200）")
    void expiredCompletedInvalidatesButLiveCompletedDoesNot() {
        PairLookup expired = lookup("0.95", RecordStatus.PENDING, RecordStatus.COMPLETED);
        expired.found().setCompletedAt(LocalDateTime.now().minusDays(31));
        when(lookupService.load(101L)).thenReturn(expired);
        assertEquals(ErrorCode.PAIR_INVALID,
                assertThrows(BizException.class, () -> contactService.view(101L, 1L, META)).errorCode());

        PairLookup live = lookup("0.95", RecordStatus.PENDING, RecordStatus.COMPLETED);
        live.found().setCompletedAt(LocalDateTime.now().minusDays(1));
        when(lookupService.load(101L)).thenReturn(live);
        assertTrue(contactService.view(101L, 1L, META).getAvailable());
    }

    @Test
    @DisplayName("非参与方：403/10007，审计 DENIED_NOT_OWNER")
    void nonParticipantIsForbidden() {
        when(lookupService.load(101L)).thenReturn(lookup("0.95", RecordStatus.PENDING, RecordStatus.PENDING));

        BizException ex = assertThrows(BizException.class, () -> contactService.view(101L, 99L, META));

        assertEquals(ErrorCode.FORBIDDEN, ex.errorCode());
        assertAudit(AccessResult.DENIED_NOT_OWNER);
    }

    @Test
    @DisplayName("任一端记录已删除：404/20004，审计 DENIED_PAIR_INVALID")
    void deletedRecordIsNotFound() {
        PairLookup lookup = lookup("0.95", RecordStatus.PENDING, RecordStatus.PENDING);
        PairLookup deleted = new PairLookup(lookup.pair(), lookup.lost(), null);
        when(lookupService.load(101L)).thenReturn(deleted);

        BizException ex = assertThrows(BizException.class, () -> contactService.view(101L, 1L, META));

        assertEquals(ErrorCode.PAIR_NOT_FOUND, ex.errorCode());
        assertAudit(AccessResult.DENIED_PAIR_INVALID);
    }

    @Test
    @DisplayName("pair 行不存在：404/20004，无法写审计（外键没有可指的行）")
    void missingPairIsNotFoundWithoutAudit() {
        when(lookupService.load(101L)).thenReturn(new PairLookup(null, null, null));

        BizException ex = assertThrows(BizException.class, () -> contactService.view(101L, 1L, META));

        assertEquals(ErrorCode.PAIR_NOT_FOUND, ex.errorCode());
        verify(accessLogService, never()).record(any(), any(), any());
    }

    @Test
    @DisplayName("按 pair 自己的 engine 取阈值：本地 0.50 可授权，语义 0.50 一律 403")
    void thresholdsFollowPairEngine() {
        PairPolicyProperties localPolicy = new PairPolicyProperties();
        localPolicy.getOverrides().getLocal().setContactUnlockThreshold(new BigDecimal("0.45"));
        localPolicy.getOverrides().getLocal().setContactDirectThreshold(new BigDecimal("0.90"));
        localPolicy.getOverrides().getLocal().setNotifyThreshold(new BigDecimal("0.90"));
        localPolicy.getOverrides().getLocal().setCompletedVisibleThreshold(new BigDecimal("0.90"));
        PairContactService localAware = new PairContactService(lookupService, localPolicy, accessLogService);

        PairLookup local = lookup("0.50", RecordStatus.PENDING, RecordStatus.PENDING);
        local.pair().setEngine("LOCAL_KEYWORD");
        when(lookupService.load(101L)).thenReturn(local);
        assertEquals("AUTHORIZABLE", localAware.view(101L, 1L, META).getTier());

        PairLookup semantic = lookup("0.50", RecordStatus.PENDING, RecordStatus.PENDING);
        when(lookupService.load(101L)).thenReturn(semantic);
        assertThrows(BizException.class, () -> contactService.view(101L, 1L, META),
                "0.50 < 默认 unlock 0.80 → 403");
    }

    private void assertAudit(AccessResult expected) {
        ArgumentCaptor<AccessResult> captor = ArgumentCaptor.forClass(AccessResult.class);
        verify(accessLogService).record(any(), captor.capture(), any());
        assertEquals(expected, captor.getValue());
    }

    private static PairLookup lookup(String score, RecordStatus lostStatus, RecordStatus foundStatus) {
        PairScore pair = new PairScore();
        pair.setId(101L);
        pair.setLostId(11L);
        pair.setFoundId(12L);
        pair.setScore(new BigDecimal(score));
        pair.setStatus(PairStatuses.NEW);
        pair.setEngine("DEEPSEEK");

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

