package com.findit.campus.review.service;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.findit.campus.record.domain.Record;
import com.findit.campus.record.domain.RecordReviewState;
import com.findit.campus.record.domain.RecordType;
import com.findit.campus.record.mapper.RecordMapper;
import com.findit.campus.review.config.ReviewProperties;
import com.findit.campus.review.postprocess.PostprocessClient;
import com.findit.campus.review.postprocess.PostprocessResult;

/** 审核编排的失败策略与规则双检优先级（不调真实 LLM）。 */
@DisplayName("ReviewGateService：通过/拒绝/重试/转人工")
class ReviewGateServiceTest {

    private static final long RECORD_ID = 9L;
    private static final String REQUEST_ID = "req-1";

    private RecordMapper recordMapper;
    private PostprocessClient client;
    private ReviewPersistence persistence;
    private ReviewGateService service;

    @BeforeEach
    void setUp() {
        recordMapper = mock(RecordMapper.class);
        client = mock(PostprocessClient.class);
        persistence = mock(ReviewPersistence.class);
        ReviewProperties properties = new ReviewProperties();
        properties.setMode("ai");
        properties.setMaxAttempts(2);
        properties.setRetryBackoffMs(0);
        when(recordMapper.selectById(RECORD_ID)).thenReturn(pendingRecord());
        service = new ReviewGateService(recordMapper, client, persistence, properties);
    }

    @Test
    @DisplayName("AI APPROVE + 规则不命中 → 落库通过并触发打分")
    void approveWritesAndTriggersScoring() {
        PostprocessResult result = approveResult("黑色皮质钱包", "在图书馆三楼捡到黑色皮质钱包",
                "图书馆三楼", "黑色皮质钱包，图书馆三楼");
        when(client.process(any())).thenReturn(result);
        when(persistence.approve(eq(RECORD_ID), eq(result), anyInt(), eq(REQUEST_ID))).thenReturn(true);

        service.process(RECORD_ID, REQUEST_ID);

        verify(persistence).approve(eq(RECORD_ID), eq(result), eq(1), eq(REQUEST_ID));
    }

    @Test
    @DisplayName("模型 APPROVE 但输出含手机号 → 规则双检覆盖，必须 REJECTED")
    void ruleDoubleCheckOverridesModelApprove() {
        PostprocessResult result = approveResult("黑色钱包 13800000001", "在图书馆三楼捡到",
                "图书馆三楼", "黑色钱包，图书馆三楼");
        when(client.process(any())).thenReturn(result);

        service.process(RECORD_ID, REQUEST_ID);

        verify(persistence).reject(eq(RECORD_ID), contains("规则双检命中"), eq(result), eq(1),
                eq(false), eq(REQUEST_ID));
        verify(persistence, never()).approve(anyLong(), any(), anyInt(), anyString());
    }

    @Test
    @DisplayName("AI 明确 REJECT → 一次调用即定稿，不重试、不触发打分")
    void modelRejectIsFinal() {
        PostprocessResult result = PostprocessResult.ok(PostprocessResult.Decision.REJECT,
                null, null, null, null, List.of("内容无法在保留特征的前提下去身份化"),
                "DEEPSEEK", "deepseek-chat", "pair-postprocess-v1", 120, 10, 5);
        when(client.process(any())).thenReturn(result);

        service.process(RECORD_ID, REQUEST_ID);

        verify(client, times(1)).process(any());
        verify(persistence).reject(eq(RECORD_ID), contains("去身份化"), eq(result), eq(1),
                eq(false), eq(REQUEST_ID));
    }

    @Test
    @DisplayName("AI 连续失败 → 停在 PENDING + 转人工，绝不触发打分")
    void consecutiveFailuresGoManual() {
        PostprocessResult unavailable = PostprocessResult.unavailable("TIMEOUT", "DEEPSEEK",
                "deepseek-chat", "pair-postprocess-v1", 30000);
        when(client.process(any())).thenReturn(unavailable);

        service.process(RECORD_ID, REQUEST_ID);

        verify(client, times(2)).process(any());
        verify(persistence).markManual(eq(RECORD_ID), contains("AI 审核连续失败"), eq(unavailable),
                eq(2), eq(REQUEST_ID));
        verify(persistence, never()).approve(anyLong(), any(), anyInt(), anyString());
    }

    @Test
    @DisplayName("已转人工的记录不再被自动重试")
    void manualRecordIsNotRetried() {
        Record record = pendingRecord();
        record.setReviewManualRequired(true);
        when(recordMapper.selectById(RECORD_ID)).thenReturn(record);

        service.process(RECORD_ID, REQUEST_ID);

        verify(client, never()).process(any());
        assertTrue(true);
    }

    private static PostprocessResult approveResult(String title, String description,
                                                   String location, String summary) {
        return PostprocessResult.ok(PostprocessResult.Decision.APPROVE, title, description, location,
                summary, List.of("已去除身份信息并保留关键特征"), "DEEPSEEK", "deepseek-chat",
                "pair-postprocess-v1", 120, 100, 50);
    }

    private static Record pendingRecord() {
        Record record = new Record();
        record.setId(RECORD_ID);
        record.setType(RecordType.LOST);
        record.setStatus(com.findit.campus.record.domain.RecordStatus.PENDING);
        record.setReviewState(RecordReviewState.PENDING);
        record.setTitle("黑色钱包 13800000001 张三");
        record.setDescription("在第三教学楼 201 丢的");
        record.setLocation("第三教学楼 201");
        record.setMatchTitle("黑色钱包 13800000001 张三");
        record.setMatchDescription("在第三教学楼 201 丢的");
        record.setMatchLocation("第三教学楼 201");
        record.setPublisherName("李同学");
        record.setCategoryName("钱包");
        record.setOccurredAt(LocalDateTime.of(2026, 10, 21, 13, 30));
        record.setReviewAttempts(0);
        record.setReviewManualRequired(false);
        return record;
    }
}
