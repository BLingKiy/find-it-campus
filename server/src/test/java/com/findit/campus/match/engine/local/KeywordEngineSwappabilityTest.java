package com.findit.campus.match.engine.local;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import com.findit.campus.category.service.CategoryService;
import com.findit.campus.common.config.MatchEngineConfig;
import com.findit.campus.match.domain.MatchResult;
import com.findit.campus.match.engine.MatchCandidate;
import com.findit.campus.match.engine.MatchCandidatePoolPort;
import com.findit.campus.match.engine.MatchCandidateSource;
import com.findit.campus.match.engine.MatchEngine;
import com.findit.campus.match.engine.MatchQuery;
import com.findit.campus.match.mapper.MatchResultMapper;
import com.findit.campus.match.service.MatchService;
import com.findit.campus.match.web.dto.MatchItemResponse;
import com.findit.campus.match.web.dto.MatchRequest;
import com.findit.campus.match.web.dto.MatchResponse;
import com.findit.campus.record.domain.RecordStatus;
import com.findit.campus.record.domain.RecordSummary;
import com.findit.campus.record.domain.RecordType;
import com.findit.campus.record.mapper.RecordMapper;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 可替换性的"会失败"的证据（{@code docs/ai-match-module.md} §6 的 V3/V4 合体）：
 *
 * <p>同一个 {@link MatchService} 构造方式、同一个请求对象、同一个调用点 {@code matchService.match(request)}，
 * 只把注入的 {@link MatchEngine} 从 {@link FakeEngine} 换成 {@link KeywordMatchEngine}，响应都满足契约。
 * 如果以后有人把实现类名写进 {@code MatchService}/{@code MatchController}，这条测试会先红。
 */
@DisplayName("引擎可替换性：同一调用点注入 Fake / Keyword 两种实现")
class KeywordEngineSwappabilityTest {

    @Test
    @DisplayName("换实现不改调用方：同一请求经两种引擎都返回合法响应")
    void sameCallSiteWorksWithEitherEngine() {
        MatchRequest request = request("我在图书馆三楼弄丢了一个黑色皮钱包，里面有银行卡");

        MatchResponse fake = serviceWith(new FakeEngine(List.of(
                new MatchCandidate(13L, new BigDecimal("0.83"), "假实现命中"),
                new MatchCandidate(26L, new BigDecimal("0.31"), "假实现弱相关")))).match(request);
        MatchResponse real = serviceWith(new KeywordMatchEngine(SeedSamples.categoryMapper())).match(request);

        // 调用方代码完全相同（上面两个 serviceWith(...).match(request)），差别只在注入的实现。
        assertEquals("FAKE_ENGINE", fake.getEngine());
        assertEquals(List.of(13L, 26L), ids(fake));
        assertEquals("LOCAL_KEYWORD", real.getEngine());
        assertNotEquals(ids(fake), ids(real), "两种实现应产出各自的结果，证明不是写死的");
        assertEquals(13L, ids(real).get(0), "本地实现 S1 的 top1 应为 13");
        assertContract(fake);
        assertContract(real);
    }

    @Test
    @DisplayName("契约由 service 兜底：引擎返回乱序/重复/超阈值时响应仍合法（与实现无关）")
    void serviceKeepsContractForAnyEngine() {
        MatchEngine scrambled = new FakeEngine(List.of(
                new MatchCandidate(13L, new BigDecimal("0.50"), "低分"),
                new MatchCandidate(13L, new BigDecimal("0.90"), "重复 id"),
                new MatchCandidate(20L, new BigDecimal("0.20"), "低于阈值")));
        MatchResponse response = serviceWith(scrambled).match(request("随便一段描述文本"));

        assertEquals(List.of(13L), ids(response), "去重后保留先出现的一条；低于 minScore 的丢弃");
        assertEquals(0, response.getItems().get(0).getScore().compareTo(new BigDecimal("0.50")));
    }

    // ------------------------------------------------------------------ 辅助

    private MatchService serviceWith(MatchEngine engine) {
        MatchCandidatePoolPort pool = mock(MatchCandidatePoolPort.class);
        when(pool.load(any(), any(), anyInt())).thenReturn(SeedSamples.pool("FOUND"));

        RecordMapper recordMapper = mock(RecordMapper.class);
        when(recordMapper.selectSummariesByIds(any(), any())).thenAnswer(invocation -> {
            List<Long> ids = invocation.getArgument(0);
            return ids.stream().map(KeywordEngineSwappabilityTest::summary).toList();
        });

        MatchResultMapper matchResultMapper = mock(MatchResultMapper.class);
        when(matchResultMapper.insert(any(MatchResult.class))).thenReturn(1);

        CategoryService categoryService = mock(CategoryService.class);

        @SuppressWarnings("unchecked")
        ObjectProvider<MatchEngine> engineProvider = mock(ObjectProvider.class);
        when(engineProvider.getIfAvailable()).thenReturn(engine);

        return new MatchService(engineProvider, pool, recordMapper, matchResultMapper, categoryService,
                new MatchEngineConfig("local"), new com.findit.campus.pair.config.PairPolicyProperties(),
                new ObjectMapper(), 500);
    }

    private static MatchRequest request(String text) {
        MatchRequest request = new MatchRequest();
        request.setText(text);
        request.setType("FOUND");
        request.setTopN(5);
        request.setMinScore(new BigDecimal("0.3"));
        return request;
    }

    private static RecordSummary summary(Long id) {
        RecordSummary summary = new RecordSummary();
        summary.setId(id);
        summary.setType(RecordType.FOUND);
        summary.setStatus(RecordStatus.PENDING);
        summary.setTitle("标题" + id);
        summary.setCategoryName("钱包卡证");
        summary.setLocation("图书馆");
        summary.setOccurredAt(LocalDateTime.of(2026, 10, 20, 15, 0, 0));
        summary.setCreatedAt(LocalDateTime.of(2026, 10, 20, 15, 30, 0));
        return summary;
    }

    private static List<Long> ids(MatchResponse response) {
        return response.getItems().stream().map(MatchItemResponse::getRecordId).toList();
    }

    private static void assertContract(MatchResponse response) {
        List<MatchItemResponse> items = response.getItems();
        assertTrue(items.size() <= response.getTopN(), "条数不得超过 topN");
        Set<Long> unique = new HashSet<>();
        BigDecimal previous = null;
        for (MatchItemResponse item : items) {
            assertTrue(unique.add(item.getRecordId()), "recordId 不得重复");
            assertTrue(item.getScore().compareTo(BigDecimal.ZERO) >= 0
                    && item.getScore().compareTo(BigDecimal.ONE) <= 0, "score 必须在 [0,1]");
            assertTrue(item.getScore().compareTo(response.getMinScore()) >= 0, "score 必须 ≥ minScore");
            if (previous != null) {
                assertTrue(previous.compareTo(item.getScore()) >= 0, "必须降序");
            }
            previous = item.getScore();
        }
        assertEquals(items.size(), response.getTotal(), "total 应等于 items 条数");
        assertFalse(response.getEngine() == null || response.getEngine().isBlank(), "响应必须带引擎标识");
    }

    /** 假实现：只吐固定结果，证明 service 只认接口、不需要知道算法。 */
    private record FakeEngine(List<MatchCandidate> result) implements MatchEngine {

        @Override
        public String impl() {
            return "FAKE_ENGINE";
        }

        @Override
        public List<MatchCandidate> match(List<MatchCandidateSource> candidates, MatchQuery query) {
            return result;
        }
    }
}
