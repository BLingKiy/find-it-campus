package com.findit.campus.match.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import com.findit.campus.category.service.CategoryService;
import com.findit.campus.common.api.ErrorCode;
import com.findit.campus.common.config.MatchEngineConfig;
import com.findit.campus.common.error.BizException;
import com.findit.campus.match.domain.MatchResult;
import com.findit.campus.match.engine.MatchCandidate;
import com.findit.campus.match.engine.MatchCandidatePoolPort;
import com.findit.campus.match.engine.MatchCandidateSource;
import com.findit.campus.match.engine.MatchEngine;
import com.findit.campus.match.engine.MatchEngineException;
import com.findit.campus.match.engine.MatchQuery;
import com.findit.campus.match.mapper.MatchResultMapper;
import com.findit.campus.match.web.dto.MatchRequest;
import com.findit.campus.match.web.dto.MatchResponse;
import com.findit.campus.record.domain.RecordStatus;
import com.findit.campus.record.domain.RecordSummary;
import com.findit.campus.record.domain.RecordType;
import com.findit.campus.record.mapper.RecordMapper;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * {@code MatchService} 的编排测试，用<b>假实现</b>注入（{@code docs/ai-match-module.md} §6 的 V3 验收）。
 *
 * <p>它证明的不是"算法对不对"（那是 3.2 的事），而是三件结构性事实：
 * <ol>
 *   <li>{@code MatchService} 只需要一个 {@link MatchEngine} 就能跑 —— 换实现不改它；</li>
 *   <li>契约 §8.2 对 {@code items} 的六条保证（倒序、≤topN、≥minScore、去重、排除自身、只含合法记录）
 *       由 <b>service</b> 兜住，不依赖引擎自觉遵守；</li>
 *   <li>引擎抛 30002/30003 时，响应侧的 {@code data.engine} 与错误码都不被吞掉。</li>
 * </ol>
 */
@DisplayName("MatchService 编排（假引擎注入）")
class MatchServiceTest {

    private MatchCandidatePoolPort poolPort;
    private RecordMapper recordMapper;
    private MatchResultMapper matchResultMapper;
    private CategoryService categoryService;
    private ObjectProvider<MatchEngine> engineProvider;
    private MatchService matchService;

    @BeforeEach
    void setUp() {
        poolPort = mock(MatchCandidatePoolPort.class);
        recordMapper = mock(RecordMapper.class);
        matchResultMapper = mock(MatchResultMapper.class);
        categoryService = mock(CategoryService.class);
        engineProvider = mock(ObjectProvider.class);
        // 快照写入要把自增主键回填到实体上，mock 里手动模拟 useGeneratedKeys 的行为
        when(matchResultMapper.insert(any(MatchResult.class))).thenAnswer(invocation -> {
            MatchResult result = invocation.getArgument(0);
            result.setId(41L);
            return 1;
        });
        matchService = new MatchService(engineProvider, poolPort, recordMapper, matchResultMapper, categoryService,
                new MatchEngineConfig("local"), new com.findit.campus.pair.config.PairPolicyProperties(),
                new ObjectMapper(), 500);
    }

    @Test
    @DisplayName("假引擎的输出直接成为响应 items：证明契约不绑定具体实现")
    void fakeEngineDrivesResponse() throws Exception {
        useEngine(new FakeEngine(List.of(
                candidate(13L, "0.83", "标题命中"),
                candidate(26L, "0.31", "弱相关"))));
        when(poolPort.load(any(), any(), anyInt())).thenReturn(List.of(source(13L), source(26L)));
        when(recordMapper.selectSummariesByIds(eq(List.of(13L, 26L)), any())).thenReturn(List.of(summary(13L), summary(26L)));

        MatchResponse response = matchService.match(request("在图书馆丢了一个黑色钱包", "FOUND", 5, "0.3", null));

        assertEquals("LOCAL_KEYWORD", response.getEngine());
        assertEquals("FOUND", response.getInputType());
        assertEquals(2, response.getTotal());
        assertEquals(List.of(13L, 26L), response.getItems().stream().map(it -> it.getRecordId()).toList());
        assertEquals(83, response.getItems().get(0).getScorePercent());
        assertEquals(0, new BigDecimal("0.83").compareTo(response.getItems().get(0).getScore()));
        assertEquals(41L, response.getMatchId());
        verify(categoryService).requireExists(isNull());
    }

    @Test
    @DisplayName("排序：score 倒序；同分按 recordId 升序（§8.4 保证可复现）")
    void stableOrdering() {
        useEngine(new FakeEngine(List.of(
                candidate(20L, "0.50", "同分"),
                candidate(10L, "0.50", "同分"),
                candidate(30L, "0.90", "高分"))));
        when(poolPort.load(any(), any(), anyInt())).thenReturn(List.of(source(10L), source(20L), source(30L)));
        when(recordMapper.selectSummariesByIds(any(), any())).thenReturn(List.of(summary(10L), summary(20L), summary(30L)));

        MatchResponse response = matchService.match(request("随便一段描述文本", null, 5, "0.3", null));

        assertEquals(List.of(30L, 10L, 20L), response.getItems().stream().map(it -> it.getRecordId()).toList());
        assertTrue(response.getInputType() == null, "不传 type 时 inputType 为 null（契约 §8.1 允许不限类型）");
    }

    @Test
    @DisplayName("过滤先于截断：低于 minScore、重复、被排除自身、回表查不到的候选全部去掉，且不足时不补齐")
    void filterBeforeTruncate() {
        useEngine(new FakeEngine(List.of(
                candidate(1L, "0.90", "高分"),
                candidate(2L, "0.10", "低于阈值"),
                candidate(1L, "0.80", "重复 recordId"),
                candidate(3L, "0.70", "记录已被删除"),
                candidate(99L, "0.60", "被排除自身"))));
        when(poolPort.load(any(), any(), anyInt())).thenReturn(List.of(source(1L), source(2L), source(3L)));
        // 3 号回表查不到（已删除/已认领），99 号被 excludeRecordId 排除
        when(recordMapper.selectSummariesByIds(any(), any())).thenReturn(List.of(summary(1L), summary(2L)));

        MatchResponse response = matchService.match(request("一段用于匹配的描述", "FOUND", 5, "0.3", 99L));

        assertEquals(List.of(1L), response.getItems().stream().map(it -> it.getRecordId()).toList());
        assertEquals(1, response.getTotal(), "过滤后不足 topN 就少给，不许二次补齐（ai-match-module §5 步骤 6）");
    }

    @Test
    @DisplayName("topN 截断：只保留分数最高的前 N 条")
    void topNTruncation() {
        useEngine(new FakeEngine(List.of(
                candidate(1L, "0.90", "a"), candidate(2L, "0.80", "b"), candidate(3L, "0.70", "c"))));
        when(poolPort.load(any(), any(), anyInt())).thenReturn(List.of(source(1L), source(2L), source(3L)));
        when(recordMapper.selectSummariesByIds(any(), any())).thenReturn(List.of(summary(1L), summary(2L), summary(3L)));

        MatchResponse response = matchService.match(request("一段用于匹配的描述", "FOUND", 2, "0.3", null));

        assertEquals(List.of(1L, 2L), response.getItems().stream().map(it -> it.getRecordId()).toList());
    }

    @Test
    @DisplayName("无命中：200 + 空 items，不是错误（契约 §8.3）")
    void emptyResultIsNotAnError() {
        useEngine(new FakeEngine(List.of()));
        when(poolPort.load(any(), any(), anyInt())).thenReturn(List.of());

        MatchResponse response = matchService.match(request("有人在体育馆捡到泳镜吗", "FOUND", 5, "0.3", null));

        assertEquals(0, response.getTotal());
        assertTrue(response.getItems().isEmpty());
        assertNotNull(response.getMatchId(), "空结果也要写快照（§8.5：含空结果）");
    }

    @Test
    @DisplayName("text 非法 → 30001 + fieldErrors（不是通用 10001）")
    void invalidTextUsesMatchErrorCode() {
        useEngine(new FakeEngine(List.of()));

        BizException blank = assertThrows(BizException.class,
                () -> matchService.match(request("   ", "FOUND", 5, "0.3", null)));
        assertEquals(ErrorCode.MATCH_INPUT_INVALID, blank.errorCode());
        assertEquals("text", fieldOf(blank));

        BizException tooShort = assertThrows(BizException.class,
                () -> matchService.match(request("钱", "FOUND", 5, "0.3", null)));
        assertEquals(ErrorCode.MATCH_INPUT_INVALID, tooShort.errorCode());
    }

    @Test
    @DisplayName("引擎不可用 → 30002 原样上抛，并补上契约要求的 data.engine（§8.3）")
    void engineFailureKeepsErrorCodeAndData() {
        MatchEngine failing = new MatchEngine() {
            @Override
            public String impl() {
                return "EXTERNAL_EMBEDDING";
            }

            @Override
            public List<MatchCandidate> match(List<MatchCandidateSource> candidates, MatchQuery query) {
                throw MatchEngineException.unavailable("外部服务未配置 Key", null);
            }
        };
        useEngine(failing);
        when(poolPort.load(any(), any(), anyInt())).thenReturn(List.of(source(1L)));

        BizException ex = assertThrows(BizException.class,
                () -> matchService.match(request("一段用于匹配的描述", "FOUND", 5, "0.3", null)));
        assertEquals(ErrorCode.MATCH_ENGINE_UNAVAILABLE, ex.errorCode());
        assertEquals("EXTERNAL_EMBEDDING", ex.data().get("engine"));
    }

    @Test
    @DisplayName("未装配任何引擎实现 → 30002（明确失败，不假装算出空结果）")
    void missingEngineBeanIsExplicit() {
        when(engineProvider.getIfAvailable()).thenReturn(null);
        when(poolPort.load(any(), any(), anyInt())).thenReturn(List.of());

        BizException ex = assertThrows(BizException.class,
                () -> matchService.match(request("一段用于匹配的描述", "FOUND", 5, "0.3", null)));
        assertEquals(ErrorCode.MATCH_ENGINE_UNAVAILABLE, ex.errorCode());
        assertEquals("local", ex.data().get("engine"));
    }

    @Test
    @DisplayName("快照写入失败只 warn：不影响已算出的响应（§8.5）")
    void snapshotFailureDoesNotBreakResponse() {
        useEngine(new FakeEngine(List.of(candidate(13L, "0.83", "命中"))));
        when(poolPort.load(any(), any(), anyInt())).thenReturn(List.of(source(13L)));
        when(recordMapper.selectSummariesByIds(any(), any())).thenReturn(List.of(summary(13L)));
        when(matchResultMapper.insert(any(MatchResult.class))).thenThrow(new RuntimeException("DB down"));

        MatchResponse response = matchService.match(request("一段用于匹配的描述", "FOUND", 5, "0.3", null));

        assertEquals(1, response.getTotal());
        assertTrue(response.getMatchId() == null);
    }

    @Test
    @DisplayName("分类不存在 → 40001，在调引擎之前就挡掉（不做无谓计算）")
    void unknownCategoryIsRejectedBeforeEngineCall() {
        useEngine(new FakeEngine(List.of()));

        assertThrows(BizException.class, () -> {
            MatchRequest request = request("一段用于匹配的描述", "FOUND", 5, "0.3", null);
            request.setCategoryId(999L);
            org.mockito.Mockito.doThrow(BizException.of(ErrorCode.CATEGORY_NOT_FOUND))
                    .when(categoryService).requireExists(eq(999L));
            matchService.match(request);
        });
        verify(poolPort, org.mockito.Mockito.never()).load(any(), any(), anyInt());
    }

    @Test
    @DisplayName("快照写库前脱敏：input_text 与 candidates.reason 都不含手机号（A1 H8）")
    void snapshotIsMaskedBeforeWrite() {
        useEngine(new FakeEngine(List.of(candidate(13L, "0.83", "描述里带 13812345678"))));
        when(poolPort.load(any(), any(), anyInt())).thenReturn(List.of(source(13L)));
        when(recordMapper.selectSummariesByIds(any(), any())).thenReturn(List.of(summary(13L)));

        matchService.match(request("昨天 14:30 联系 13812345678", "FOUND", 5, "0.3", null));

        org.mockito.ArgumentCaptor<MatchResult> captor = org.mockito.ArgumentCaptor.forClass(MatchResult.class);
        verify(matchResultMapper).insert(captor.capture());
        MatchResult snapshot = captor.getValue();
        assertFalse(snapshot.getInputText().contains("13812345678"), snapshot.getInputText());
        assertFalse(snapshot.getCandidates().contains("13812345678"), snapshot.getCandidates());
        assertTrue(snapshot.getInputText().contains("[手机号已隐藏]"));
    }

    @Test
    @DisplayName("COMPLETED 候选：recordStatus=COMPLETED、detailAccess=RESTRICTED（命中≠详情授权）")
    void completedCandidateIsRestricted() {
        useEngine(new FakeEngine(List.of(candidate(23L, "0.95", "高分"))));
        when(poolPort.load(any(), any(), anyInt())).thenReturn(List.of(source(23L)));
        RecordSummary completed = summary(23L);
        completed.setStatus(RecordStatus.COMPLETED);
        completed.setCompletedAt(LocalDateTime.now().minusDays(3));
        when(recordMapper.selectSummariesByIds(any(), any())).thenReturn(List.of(completed));

        MatchResponse response = matchService.match(request("一段用于匹配的描述", "FOUND", 5, "0.3", null));

        assertEquals("COMPLETED", response.getItems().get(0).getRecordStatus());
        assertEquals("RESTRICTED", response.getItems().get(0).getDetailAccess());
    }

    // ------------------------------------------------------------------ 辅助

    private void useEngine(MatchEngine engine) {
        when(engineProvider.getIfAvailable()).thenReturn(engine);
    }

    private static MatchRequest request(String text, String type, int topN, String minScore, Long excludeRecordId) {
        MatchRequest request = new MatchRequest();
        request.setText(text);
        request.setType(type);
        request.setTopN(topN);
        request.setMinScore(new BigDecimal(minScore));
        request.setExcludeRecordId(excludeRecordId);
        return request;
    }

    private static MatchCandidate candidate(Long recordId, String score, String reason) {
        return new MatchCandidate(recordId, new BigDecimal(score), reason);
    }

    private static MatchCandidateSource source(Long recordId) {
        return new MatchCandidateSource(recordId, "标题" + recordId, "描述" + recordId, 1L, "图书馆");
    }

    private static RecordSummary summary(Long recordId) {
        RecordSummary summary = new RecordSummary();
        summary.setId(recordId);
        summary.setType(RecordType.FOUND);
        summary.setStatus(RecordStatus.PENDING);
        summary.setTitle("标题" + recordId);
        summary.setCategoryName("钱包卡证");
        summary.setLocation("图书馆");
        summary.setOccurredAt(LocalDateTime.of(2026, 10, 20, 15, 0, 0));
        summary.setCreatedAt(LocalDateTime.of(2026, 10, 20, 15, 30, 0));
        return summary;
    }

    @SuppressWarnings("unchecked")
    private static String fieldOf(BizException ex) {
        List<com.findit.campus.common.api.FieldError> fieldErrors =
                (List<com.findit.campus.common.api.FieldError>) ex.data().get("fieldErrors");
        return fieldErrors.get(0).field();
    }

    /** 假实现：只把固定结果吐出来，不依赖任何算法 —— 它能跑通即证明 service 只认接口。 */
    private record FakeEngine(List<MatchCandidate> result) implements MatchEngine {

        @Override
        public String impl() {
            return "LOCAL_KEYWORD";
        }

        @Override
        public List<MatchCandidate> match(List<MatchCandidateSource> candidates, MatchQuery query) {
            return result;
        }
    }
}
