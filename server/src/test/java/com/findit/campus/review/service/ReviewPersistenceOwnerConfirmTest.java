package com.findit.campus.review.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import com.findit.campus.pair.event.PairScoringRequested;
import com.findit.campus.record.mapper.RecordMapper;
import com.findit.campus.review.domain.ReviewAudit;
import com.findit.campus.review.mapper.ReviewAuditMapper;
import com.findit.campus.review.postprocess.PostprocessResult;

/**
 * 本人确认发布的事务边界：状态、审计、打分事件必须一起成立。
 *
 * <p>重点不是"能确认"，而是<b>不能发生什么</b>：SQL 影响 0 行时不写审计、不发打分事件，
 * 否则会出现"审计说已发布、实际没上架"或"没上架却触发打分"的分叉。
 */
@DisplayName("ReviewPersistence：OWNER_CONFIRM 审计与 RULE_ONLY 标记")
class ReviewPersistenceOwnerConfirmTest {

    private static final long RECORD_ID = 9L;
    private static final long OWNER = 1L;
    private static final String REQUEST_ID = "req-owner";

    private RecordMapper recordMapper;
    private ReviewAuditMapper auditMapper;
    private ApplicationEventPublisher eventPublisher;
    private ReviewPersistence persistence;

    @BeforeEach
    void setUp() {
        recordMapper = mock(RecordMapper.class);
        auditMapper = mock(ReviewAuditMapper.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        persistence = new ReviewPersistence(recordMapper, auditMapper, eventPublisher);
    }

    @Test
    @DisplayName("条件更新命中 → 写 OWNER_CONFIRM 审计（HUMAN/本人/RULE_ONLY）并触发打分")
    void ownerConfirmWritesAuditAndPublishesScoring() {
        when(recordMapper.confirmOwnerReview(eq(RECORD_ID), anyString(), any())).thenReturn(1);

        assertTrue(persistence.ownerConfirm(RECORD_ID, OWNER, REQUEST_ID));

        ArgumentCaptor<ReviewAudit> audit = ArgumentCaptor.forClass(ReviewAudit.class);
        verify(auditMapper).insert(audit.capture());
        assertEquals("OWNER_CONFIRM", audit.getValue().getAction());
        assertEquals("APPROVE", audit.getValue().getDecision());
        assertEquals("APPROVED", audit.getValue().getReviewState());
        assertEquals("RULE_ONLY", audit.getValue().getEngine(), "必须可筛出未经 AI 审核的记录");
        assertEquals("HUMAN", audit.getValue().getActorType());
        assertEquals(OWNER, audit.getValue().getActorId(), "只能记本人，不能记成 AI/系统");
        assertEquals(REQUEST_ID, audit.getValue().getRequestId());
        verify(eventPublisher).publishEvent(any(PairScoringRequested.class));
    }

    @Test
    @DisplayName("通过时采用模型 rewritten 文本（拍板 ③：改写是核心价值，默认采用）")
    void approveUsesRewrittenDisplayText() {
        PostprocessResult result = PostprocessResult.ok(PostprocessResult.Decision.APPROVE,
                "改写标题", "改写描述", "图书馆三楼", "黑色钱包，图书馆三楼",
                List.of("已去身份"), "DEEPSEEK", "deepseek-chat", "pair-postprocess-v1",
                12, 8, 4);
        when(recordMapper.approveReview(anyLong(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), anyInt(), anyInt(), anyInt(), any()))
                .thenReturn(1);

        assertTrue(persistence.approve(RECORD_ID, result, 1, REQUEST_ID));

        verify(recordMapper).approveReview(eq(RECORD_ID), eq("改写标题"), eq("改写描述"),
                eq("图书馆三楼"), eq("黑色钱包，图书馆三楼"), anyString(), eq("DEEPSEEK"),
                eq("deepseek-chat"), eq("pair-postprocess-v1"), eq(12), eq(12), eq(1), any());
    }

    @Test
    @DisplayName("条件更新 0 行（AI 正常/已被处理）→ 不写审计、不发打分事件")
    void ownerConfirmSkippedWhenNotManualRequired() {
        when(recordMapper.confirmOwnerReview(eq(RECORD_ID), anyString(), any())).thenReturn(0);

        assertFalse(persistence.ownerConfirm(RECORD_ID, OWNER, REQUEST_ID));

        verifyNoInteractions(auditMapper, eventPublisher);
    }

    @Test
    @DisplayName("删除清原文：auditRawPurge 写 RAW_PURGE，且不含任何正文")
    void rawPurgeAuditCarriesNoText() {
        persistence.auditRawPurge(RECORD_ID, OWNER, REQUEST_ID);

        ArgumentCaptor<ReviewAudit> audit = ArgumentCaptor.forClass(ReviewAudit.class);
        verify(auditMapper).insert(audit.capture());
        assertEquals("RAW_PURGE", audit.getValue().getAction());
        assertEquals(OWNER, audit.getValue().getActorId());
        assertEquals(null, audit.getValue().getReason());
        assertEquals(null, audit.getValue().getEngine());
        verify(eventPublisher, never()).publishEvent(any());
        verify(recordMapper, never()).confirmOwnerReview(anyLong(), anyString(), any());
    }
}
