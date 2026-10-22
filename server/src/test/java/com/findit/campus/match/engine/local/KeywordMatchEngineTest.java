package com.findit.campus.match.engine.local;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.findit.campus.match.engine.MatchCandidate;
import com.findit.campus.match.engine.MatchCandidateSource;
import com.findit.campus.match.engine.MatchQuery;

/**
 * S1–N4 样例集（{@code db/seed.sql} 末段）与引擎契约的回归测试。
 *
 * <p>样例文件与断言都在仓库里：样例集改了，这里会红；这里红了，答辩时"正例 top1"就不是口说无凭。
 * 外部实现（ExternalEmbeddingMatchEngine）落地后应把本类改成参数化，让 {@link #assertContract} 跑两遍。
 */
@DisplayName("KeywordMatchEngine：S1–N4 样例集 + 契约")
class KeywordMatchEngineTest {

    private static final BigDecimal MIN_SCORE = new BigDecimal("0.30");

    private final KeywordMatchEngine engine = new KeywordMatchEngine(SeedSamples.categoryMapper());

    @Test
    @DisplayName("impl() 与配置值、t_match_result.impl 值域一致")
    void implIsLocalKeyword() {
        assertEquals("LOCAL_KEYWORD", engine.impl());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("positiveSamples")
    @DisplayName("正例：目标记录出现在 top-N 且排第一")
    void positiveSampleHitsTopOne(String name, String text, String type, long expectedTop1) {
        List<MatchCandidate> result = match(type, text, 5, MIN_SCORE);

        assertFalse(result.isEmpty(), name + " 不应为空结果");
        assertEquals(expectedTop1, result.get(0).recordId(), name + " top1 应为 " + expectedTop1);
        assertContract(result, 5, MIN_SCORE);
        assertNotNull(result.get(0).reason());
        assertFalse(result.get(0).reason().isBlank());
    }

    @Test
    @DisplayName("N1：目标 7 命中，13–22 的负例全部低于 minScore（无高分误报）")
    void negativeNObjecCalculator() {
        List<MatchCandidate> result = match(null, "捡到过一个计算器吗？灰色的卡西欧", 5, MIN_SCORE);

        assertFalse(result.isEmpty());
        assertEquals(7L, result.get(0).recordId(), "计算器样例 top1 应为 7");
        Set<Long> ids = ids(result);
        for (long id = 13; id <= 22; id++) {
            assertFalse(ids.contains(id), "13–22 是负例，不应进入结果：" + id);
        }
        assertContract(result, 5, MIN_SCORE);
    }

    @Test
    @DisplayName("N2：无命中返回空列表，不得出现高分误报")
    void negativeEmptyResult() {
        List<MatchCandidate> result = match("FOUND", "有人在体育馆捡到泳镜吗", 5, MIN_SCORE);
        assertTrue(result.isEmpty(), "泳镜样例不应命中任何招领记录，实际：" + describe(result));
    }

    @Test
    @DisplayName("N3：4/16 都命中，且都高于 18（钥匙）")
    void negativeUmbrellaBeatsKeys() {
        List<MatchCandidate> result = match(null, "丢了一把雨伞", 5, MIN_SCORE);
        Set<Long> ids = ids(result);

        assertTrue(ids.contains(4L), "应命中 4 号（深蓝色折叠雨伞），实际：" + describe(result));
        assertTrue(ids.contains(16L), "应命中 16 号（食堂折叠伞），实际：" + describe(result));
        assertFalse(ids.contains(18L), "钥匙记录不应进入雨伞结果（IDF + 类目词表应把它压到阈值下）");
        assertContract(result, 5, MIN_SCORE);
    }

    @Test
    @DisplayName("N4：18 号 top1；23（CLAIMED）/25（已删除）不在候选池，更不在结果")
    void negativeKeysOnlyPending() {
        List<MatchCandidateSource> pool = SeedSamples.pool("FOUND");
        Set<Long> poolIds = new HashSet<>();
        pool.forEach(source -> poolIds.add(source.recordId()));
        assertFalse(poolIds.contains(23L), "CLAIMED 记录不应进候选池");
        assertFalse(poolIds.contains(25L), "逻辑删除记录不应进候选池");
        assertEquals(21, SeedSamples.pool(null).size(), "未删除 + PENDING 应为 21 条（seed 注释口径）");

        List<MatchCandidate> result = match("FOUND", "捡到一串钥匙", 5, MIN_SCORE);
        assertEquals(18L, result.get(0).recordId());
        assertFalse(ids(result).contains(23L));
        assertFalse(ids(result).contains(25L));
        assertContract(result, 5, MIN_SCORE);
    }

    @Test
    @DisplayName("契约：降序、≤topN、≥minScore、去重、score ∈ [0,1] 且 scale=2")
    void respectsContractLimits() {
        List<MatchCandidate> result = match("FOUND", "我在图书馆三楼弄丢了一个黑色皮钱包", 3, new BigDecimal("0.10"));
        assertContract(result, 3, new BigDecimal("0.10"));
        assertEquals(3, result.size(), "minScore=0.10 时 S1 的候选足够，topN=3 应返回 3 条");
    }

    @Test
    @DisplayName("minScore 是在引擎里过滤的：阈值 0.9 只剩最高分")
    void filtersByMinScore() {
        List<MatchCandidate> result = match("FOUND", "我在图书馆三楼弄丢了一个黑色皮钱包", 5, new BigDecimal("0.90"));

        assertEquals(1, result.size(), "只有 13 号应 ≥ 0.90，实际：" + describe(result));
        assertEquals(13L, result.get(0).recordId());
        assertContract(result, 5, new BigDecimal("0.90"));
    }

    @Test
    @DisplayName("同一输入两次调用结果完全一致（本地实现必须可复现）")
    void deterministic() {
        List<MatchCandidate> first = match("FOUND", "操场跑步忘拿了白色 iPhone，透明壳带小熊贴纸", 5, MIN_SCORE);
        List<MatchCandidate> second = match("FOUND", "操场跑步忘拿了白色 iPhone，透明壳带小熊贴纸", 5, MIN_SCORE);

        assertEquals(describe(first), describe(second));
    }

    @Test
    @DisplayName("空候选池 / 空查询 → 空列表，不抛异常")
    void emptyInputsAreSafe() {
        assertTrue(engine.match(List.of(), new MatchQuery("任意文本", null, null, 5, MIN_SCORE)).isEmpty());
        assertTrue(engine.match(null, new MatchQuery("任意文本", null, null, 5, MIN_SCORE)).isEmpty());
        assertTrue(engine.match(SeedSamples.pool("FOUND"), null).isEmpty());
    }

    @Test
    @DisplayName("完全无关的文本不产生命中（非空池）")
    void unrelatedQueryReturnsEmpty() {
        List<MatchCandidate> result = match("FOUND", "zzzz qqqq", 5, MIN_SCORE);
        assertTrue(result.isEmpty(), "无关文本不应命中，实际：" + describe(result));
    }

    @Test
    @DisplayName("类目词表不可用只降级为纯文本匹配，不抛异常、不返回空")
    void categoryLexiconFailureDegradesGracefully() {
        KeywordMatchEngine degraded = new KeywordMatchEngine(SeedSamples.failingCategoryMapper());

        List<MatchCandidate> result = degraded.match(SeedSamples.pool("FOUND"),
                new MatchQuery("我在图书馆三楼弄丢了一个黑色皮钱包，里面有银行卡", null, null, 5, MIN_SCORE));

        assertFalse(result.isEmpty(), "词表失败不应让匹配返回空");
        assertEquals(13L, result.get(0).recordId());
        assertTrue(result.get(0).score().compareTo(new BigDecimal("0.50")) > 0,
                "纯文本分仍应明显高于阈值，实际 " + result.get(0).score());
        assertContract(result, 5, MIN_SCORE);
    }

    // ------------------------------------------------------------------ 辅助

    private List<MatchCandidate> match(String type, String text, int topN, BigDecimal minScore) {
        return engine.match(SeedSamples.pool(type),
                new MatchQuery(text, SeedSamples.typeOf(type), null, topN, minScore));
    }

    private static void assertContract(List<MatchCandidate> result, int topN, BigDecimal minScore) {
        assertNotNull(result, "契约：结果非 null（无命中返回空列表）");
        assertTrue(result.size() <= topN, "结果条数不得超过 topN");

        Set<Long> unique = new HashSet<>();
        BigDecimal previous = null;
        for (MatchCandidate candidate : result) {
            assertNotNull(candidate.recordId(), "recordId 不得为空");
            assertTrue(unique.add(candidate.recordId()), "recordId 不得重复：" + candidate.recordId());

            BigDecimal score = candidate.score();
            assertNotNull(score, "score 不得为空");
            assertTrue(score.compareTo(BigDecimal.ZERO) >= 0 && score.compareTo(BigDecimal.ONE) <= 0,
                    "score 必须落在 [0,1]：" + score);
            assertEquals(2, score.scale(), "score 应统一到 2 位小数：" + score);
            assertTrue(score.compareTo(minScore) >= 0, "score 必须 ≥ minScore：" + score);
            if (previous != null) {
                assertTrue(previous.compareTo(score) >= 0, "必须按 score 降序：" + previous + " -> " + score);
            }
            previous = score;
        }
    }

    private static Set<Long> ids(List<MatchCandidate> result) {
        Set<Long> ids = new HashSet<>();
        result.forEach(candidate -> ids.add(candidate.recordId()));
        return ids;
    }

    private static String describe(List<MatchCandidate> result) {
        List<String> parts = new ArrayList<>();
        result.forEach(candidate -> parts.add("#" + candidate.recordId() + "=" + candidate.score()));
        return parts.isEmpty() ? "[]" : String.join(", ", parts);
    }

    static Stream<Arguments> positiveSamples() {
        return Stream.of(
                Arguments.of("S1 图书馆丢黑色钱包", "我在图书馆三楼弄丢了一个黑色皮钱包，里面有银行卡", "FOUND", 13L),
                Arguments.of("S2 操场忘拿白色 iPhone", "操场跑步忘拿了白色 iPhone，透明壳带小熊贴纸", "FOUND", 14L),
                Arguments.of("S3 高数课学生证校园卡", "高数课上的学生证校园卡不见了，姓名张三", "FOUND", 15L),
                Arguments.of("S4 食堂丢深蓝自动伞", "食堂丢了把深蓝自动伞，木头伞柄", "FOUND", 16L),
                Arguments.of("S5 白色无线耳机充电盒", "白色的无线耳机加充电盒，右耳有划痕", "FOUND", 17L),
                Arguments.of("S6 快递点钥匙串小熊挂饰", "快递点掉的钥匙串，黄色小熊挂饰", "FOUND", 18L));
    }
}
