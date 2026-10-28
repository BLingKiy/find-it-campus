package com.findit.campus.review.ab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.findit.campus.category.mapper.CategoryMapper;
import com.findit.campus.pair.config.LlmProperties;
import com.findit.campus.pair.domain.PairSide;
import com.findit.campus.pair.engine.LlmCircuitBreaker;
import com.findit.campus.pair.engine.PairPrompt;
import com.findit.campus.pair.engine.PairScoreCommand;
import com.findit.campus.pair.engine.PairScoreResult;
import com.findit.campus.pair.engine.PairScoringMetrics;
import com.findit.campus.pair.engine.deepseek.DeepSeekPairScorer;
import com.findit.campus.pair.engine.local.LocalKeywordPairScorer;
import com.findit.campus.record.domain.RecordStatus;
import com.findit.campus.record.domain.RecordType;
import com.findit.campus.review.config.ReviewProperties;
import com.findit.campus.review.postprocess.DeepSeekPostprocessClient;
import com.findit.campus.review.postprocess.IdentityScrubber;
import com.findit.campus.review.postprocess.PostprocessCommand;
import com.findit.campus.review.postprocess.PostprocessResult;

/**
 * A/B 实测脚本（需要真实 {@code DEEPSEEK_API_KEY}，默认不跑）。
 *
 * <p>运行：
 * <pre>
 *   $env:DEEPSEEK_API_KEY = (Get-Content ... -Raw).Trim()
 *   mvn -Dtest=ReviewAbHarnessTest test
 * </pre>
 *
 * <p>比较的是两条通道：
 * <ul>
 *   <li>{@code BEFORE} = {@code match_text}：原文仅剥身份（生产出分口径）；</li>
 *   <li>{@code AFTER} = {@code display_text}：LLM 改写后的公示文本（反事实：如果单通道会怎样）。</li>
 * </ul>
 * 生产出分固定用 BEFORE，所以真实分数差按构造为 0；AFTER 只用于证明「单通道会有风险」，
 * 以及给 prompt 调优提供数据。输出写入 {@code docs/testing/v2/Z85-review-gate/ab/}。
 */
@DisplayName("A/B：改写对匹配的影响（真实 DeepSeek，需 key）")
class ReviewAbHarnessTest {

    private static final BigDecimal THRESHOLD = new BigDecimal("0.75");
    private static final String PUBLISHER = "李同学";
    private static final LocalDateTime OCCURRED = LocalDateTime.of(2026, 10, 21, 13, 30);

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("15 对样本 × 2 引擎 × match/display 两通道")
    void run() throws Exception {
        String key = System.getenv("DEEPSEEK_API_KEY");
        Assumptions.assumeTrue(key != null && !key.isBlank(),
                "DEEPSEEK_API_KEY 为空，跳过 A/B（不伪造数据）");

        LlmProperties llm = new LlmProperties();
        llm.setBaseUrl(System.getenv().getOrDefault("FINDIT_LLM_BASE_URL", "https://api.deepseek.com/v1"));
        llm.setModel(System.getenv().getOrDefault("FINDIT_LLM_MODEL", "deepseek-chat"));
        llm.setConnectTimeoutMs(10_000);
        llm.setReadTimeoutMs(60_000);
        llm.setTotalTimeoutMs(70_000);

        ReviewProperties review = new ReviewProperties();
        review.setTimeoutMs(60_000);
        review.setMaxTokens(900);
        DeepSeekPostprocessClient postprocess = new DeepSeekPostprocessClient(llm, review, objectMapper, key);

        List<Fixture> fixtures = fixtures();
        assertEquals(15, fixtures.size(), "A/B 样本必须 ≥15 对");

        Map<String, Channel> channels = new LinkedHashMap<>();
        channels.put("BEFORE", new Channel());
        channels.put("AFTER", new Channel());

        for (Fixture fixture : fixtures) {
            Text lostMatch = identityText(fixture.lostTitle, fixture.lostDescription, fixture.lostLocation);
            Text foundMatch = identityText(fixture.foundTitle, fixture.foundDescription, fixture.foundLocation);
            PostprocessResult lostDisplay = processWithRetry(postprocess, fixture, RecordType.LOST, lostMatch);
            PostprocessResult foundDisplay = processWithRetry(postprocess, fixture, RecordType.FOUND, foundMatch);
            assertEquals(PostprocessResult.Decision.APPROVE, lostDisplay.decision(), "LOST 应通过: " + fixture.id);
            assertEquals(PostprocessResult.Decision.APPROVE, foundDisplay.decision(), "FOUND 应通过: " + fixture.id);

            channels.get("BEFORE").pairs.add(new PairSample(fixture, side(fixture, RecordType.LOST, lostMatch),
                    side(fixture, RecordType.FOUND, foundMatch)));
            channels.get("AFTER").pairs.add(new PairSample(fixture,
                    side(fixture, RecordType.LOST, new Text(lostDisplay.title(), lostDisplay.description(),
                            lostDisplay.location())),
                    side(fixture, RecordType.FOUND, new Text(foundDisplay.title(), foundDisplay.description(),
                            foundDisplay.location()))));
        }

        // 排序质量子集：前 5 个正例 LOST 对 3 个 FOUND（正例 + 两个负例）排序。
        List<RankQuery> rankQueries = rankQueries(channels.get("BEFORE").pairs);
        channels.get("BEFORE").rankQueries.addAll(rankQueries);
        channels.get("AFTER").rankQueries.addAll(rankQueries(channels.get("AFTER").pairs));

        DeepSeekPairScorer deepseek = new DeepSeekPairScorer(llm, objectMapper,
                new LlmCircuitBreaker(llm), new PairScoringMetrics(), key);
        LocalKeywordPairScorer local = new LocalKeywordPairScorer(new EmptyProvider());

        Map<String, Object> summary = new LinkedHashMap<>();
        for (String engine : List.of("DEEPSEEK", "LOCAL_KEYWORD")) {
            for (String channel : List.of("BEFORE", "AFTER")) {
                Channel data = channels.get(channel);
                for (PairSample sample : data.pairs) {
                    BigDecimal score = score(engine, deepseek, local, sample.lost, sample.found);
                    data.scores.put(sample.fixture.id, score);
                    sample.scored = score;
                }
                for (RankQuery query : data.rankQueries) {
                    List<BigDecimal> scores = new ArrayList<>();
                    for (PairSide candidate : query.candidates) {
                        scores.add(score(engine, deepseek, local, query.lost, candidate));
                    }
                    query.result = scores;
                }
                summary.put(engine + "_" + channel, summarize(engine, channel, data));
            }
        }

        Path outDir = Path.of("..", "docs", "testing", "v2", "Z85-review-gate", "ab").toAbsolutePath().normalize();
        Files.createDirectories(outDir);
        Files.writeString(outDir.resolve("ab-result.json"),
                objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(summary), StandardCharsets.UTF_8);
        Files.writeString(outDir.resolve("ab-samples.csv"), csv(summary), StandardCharsets.UTF_8);
        Files.writeString(outDir.resolve("ab-summary.md"), markdown(summary), StandardCharsets.UTF_8);
        System.out.println("A/B written to " + outDir);
        assertTrue(Files.exists(outDir.resolve("ab-result.json")));
    }

    private static Text identityText(String title, String description, String location) {
        return new Text(IdentityScrubber.scrub(title, PUBLISHER),
                IdentityScrubber.scrub(description, PUBLISHER),
                IdentityScrubber.scrub(location, PUBLISHER));
    }

    private static PostprocessResult processWithRetry(DeepSeekPostprocessClient client, Fixture fixture,
                                                      RecordType type, Text text) throws InterruptedException {
        PostprocessCommand command = new PostprocessCommand(fixture.id.hashCode(), type, fixture.category,
                text.title, text.description, text.location, OCCURRED, "pair-postprocess-v1");
        PostprocessResult last = null;
        for (int i = 0; i < 3; i++) {
            last = client.process(command);
            if (last.ok()) {
                return last;
            }
            Thread.sleep(500L * (i + 1));
        }
        throw new AssertionError("postprocess failed for " + fixture.id + ": "
                + (last == null ? "null" : last.errorReason()));
    }

    private static BigDecimal score(String engine, DeepSeekPairScorer deepseek, LocalKeywordPairScorer local,
                                    PairSide lost, PairSide found) throws InterruptedException {
        PairScoreCommand command = new PairScoreCommand(lost, found, PairPrompt.VERSION);
        if ("LOCAL_KEYWORD".equals(engine)) {
            return local.score(command).score();
        }
        for (int i = 0; i < 3; i++) {
            PairScoreResult result = deepseek.score(command);
            if (result.hasScore() && "DEEPSEEK".equals(result.engine())) {
                Thread.sleep(150);
                return result.score();
            }
            Thread.sleep(500L * (i + 1));
        }
        throw new AssertionError("pair scoring failed for " + lost.recordId() + "->" + found.recordId());
    }

    private static Map<String, Object> summarize(String engine, String channel, Channel data) {
        List<PairSample> positives = data.pairs.stream().filter(p -> p.fixture.positive).toList();
        List<PairSample> negatives = data.pairs.stream().filter(p -> !p.fixture.positive).toList();
        BigDecimal positiveConsistency = ratio(positives.stream().filter(p -> p.scored.compareTo(THRESHOLD) >= 0).count(),
                positives.size());
        BigDecimal negativeMis = ratio(negatives.stream().filter(p -> p.scored.compareTo(THRESHOLD) >= 0).count(),
                negatives.size());
        int top1 = 0;
        for (RankQuery query : data.rankQueries) {
            int best = 0;
            for (int i = 1; i < query.result.size(); i++) {
                if (query.result.get(i).compareTo(query.result.get(best)) > 0) {
                    best = i;
                }
            }
            if (best == 0) {
                top1++;
            }
        }
        BigDecimal top1Rate = ratio(top1, data.rankQueries.size());
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("engine", engine);
        map.put("channel", channel);
        map.put("pairs", data.pairs.size());
        map.put("positiveConsistency", positiveConsistency);
        map.put("negativeMisMatchRate", negativeMis);
        map.put("top1HitRate", top1Rate);
        map.put("scores", data.pairs.stream().collect(java.util.stream.Collectors.toMap(
                p -> p.fixture.id, p -> p.scored, (a, b) -> a, LinkedHashMap::new)));
        return map;
    }

    private static BigDecimal ratio(long numerator, long denominator) {
        if (denominator == 0) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(numerator).divide(BigDecimal.valueOf(denominator), 4, RoundingMode.HALF_UP);
    }

    private static List<RankQuery> rankQueries(List<PairSample> pairs) {
        List<PairSample> positives = pairs.stream().filter(p -> p.fixture.positive).limit(5).toList();
        List<PairSample> negatives = pairs.stream().filter(p -> !p.fixture.positive).toList();
        List<RankQuery> queries = new ArrayList<>();
        for (int i = 0; i < positives.size(); i++) {
            PairSample positive = positives.get(i);
            List<PairSide> candidates = new ArrayList<>();
            candidates.add(positive.found);
            candidates.add(negatives.get(i % negatives.size()).found);
            candidates.add(negatives.get((i + 1) % negatives.size()).found);
            queries.add(new RankQuery(positive.lost, candidates));
        }
        return queries;
    }

    private static PairSide side(Fixture fixture, RecordType type, Text text) {
        long id = (fixture.id + type.name()).hashCode() & 0x7fffffffL;
        return new PairSide(id, type, RecordStatus.PENDING, null, text.title, text.description,
                text.location, 1L, fixture.category, OCCURRED, 1L);
    }

    @SuppressWarnings("unchecked")
    private static String csv(Map<String, Object> summary) {
        StringBuilder builder = new StringBuilder("engine,channel,pair,positive,score\n");
        for (String key : List.of("DEEPSEEK_BEFORE", "DEEPSEEK_AFTER",
                "LOCAL_KEYWORD_BEFORE", "LOCAL_KEYWORD_AFTER")) {
            Map<String, Object> row = (Map<String, Object>) summary.get(key);
            Map<String, Object> scores = (Map<String, Object>) row.get("scores");
            for (Map.Entry<String, Object> entry : scores.entrySet()) {
                builder.append(row.get("engine")).append(',').append(row.get("channel")).append(',')
                        .append(entry.getKey()).append(',').append(entry.getKey().startsWith("P"))
                        .append(',').append(entry.getValue()).append('\n');
            }
        }
        return builder.toString();
    }

    private static String markdown(Map<String, Object> summary) {
        StringBuilder builder = new StringBuilder("# Z85 改写 A/B 实测\n\n");
        builder.append("| 引擎 | 通道 | 正例一致率 | 负例误配率 | top1 命中率 |\n");
        builder.append("|---|---|---|---|---|\n");
        for (String key : List.of("DEEPSEEK_BEFORE", "DEEPSEEK_AFTER",
                "LOCAL_KEYWORD_BEFORE", "LOCAL_KEYWORD_AFTER")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> row = (Map<String, Object>) summary.get(key);
            builder.append("| ").append(row.get("engine")).append(" | ").append(row.get("channel"))
                    .append(" | ").append(row.get("positiveConsistency"))
                    .append(" | ").append(row.get("negativeMisMatchRate"))
                    .append(" | ").append(row.get("top1HitRate")).append(" |\n");
        }
        builder.append("\n> BEFORE=match_text（生产出分口径）；AFTER=display_text（反事实单通道）。")
                .append("原始分数见 ab-result.json / ab-samples.csv。\n");
        return builder.toString();
    }

    private static List<Fixture> fixtures() {
        List<Fixture> list = new ArrayList<>();
        // 10 正例：同一物品，不同措辞；含身份信息与精准地点，验证剥离/改写。
        add(list, "P01", true, "钱包", "黑色皮质钱包", "姓名：张三 电话 13800000011 在图书馆三楼丢失黑色皮质钱包，内有工行卡",
                "第三教学楼 201", "捡到黑色皮质钱包，内有工商银行卡", "图书馆三楼", PUBLISHER);
        add(list, "P02", true, "手机", "白色 iPhone 14", "白色 iPhone 14，透明壳，背面小熊贴纸，南操场跑道边丢的，电话 13800000012",
                "南操场跑道边", "在南操场捡到白色 iPhone 14，透明壳有小熊贴纸", "南操场", PUBLISHER);
        add(list, "P03", true, "雨伞", "深蓝色折叠雨伞", "深蓝色自动折叠伞，木柄，伞面有小破洞，落在第二食堂二楼靠窗桌",
                "第二食堂二楼", "第二食堂二楼捡到深蓝色折叠伞，木柄，伞面有破洞", "第二食堂二楼", PUBLISHER);
        add(list, "P04", true, "水杯", "蓝色保温杯", "蓝色星巴克 logo 保温杯，第三教学楼 201 教室落下，姓名：李四",
                "第三教学楼 201 教室", "在第三教学楼 201 捡到蓝色星巴克保温杯", "第三教学楼 201", PUBLISHER);
        add(list, "P05", true, "证件", "学生证", "学生证，姓名：王五，学号 2023123456，第三教学楼丢失",
                "第三教学楼", "捡到一张学生证，姓名王五，学号 2023123456", "第三教学楼", PUBLISHER);
        add(list, "P06", true, "电脑", "银色 MacBook Air", "银色 MacBook Air，A 面贴了宇航员贴纸，图书馆四楼自习区",
                "图书馆四楼", "图书馆四楼捡到银色 MacBook Air，宇航员贴纸", "图书馆四楼", PUBLISHER);
        add(list, "P07", true, "耳机", "黑色蓝牙耳机", "黑色蓝牙耳机，充电盒有划痕，体育馆看台丢的",
                "体育馆看台", "体育馆看台捡到黑色蓝牙耳机，充电盒有划痕", "体育馆", PUBLISHER);
        add(list, "P08", true, "衣物", "红色羊毛围巾", "红色羊毛围巾，北门附近丢的，电话 13800000013",
                "北门", "北门捡到红色羊毛围巾", "北门", PUBLISHER);
        add(list, "P09", true, "包", "灰色双肩包", "灰色双肩包，有电脑夹层，实验楼 302 落下",
                "实验楼 302", "实验楼 302 捡到灰色双肩包，有电脑夹层", "实验楼", PUBLISHER);
        add(list, "P10", true, "耳机", "白色 AirPods", "白色 AirPods 右耳，食堂二楼丢的，姓名：赵六",
                "第二食堂二楼", "第二食堂二楼捡到白色 AirPods 右耳", "第二食堂二楼", PUBLISHER);
        // 5 负例：同类别/同地点但不同物品。
        add(list, "N01", false, "钱包", "黑色皮质钱包", "黑色皮质钱包，内有工行卡，图书馆三楼",
                "图书馆三楼", "棕色帆布钱包，内有校园卡，图书馆三楼", "图书馆三楼", PUBLISHER);
        add(list, "N02", false, "手机", "白色 iPhone 14", "白色 iPhone 14，透明壳，南操场",
                "南操场", "黑色 iPhone 13，磨砂壳，南操场", "南操场", PUBLISHER);
        add(list, "N03", false, "雨伞", "深蓝色折叠雨伞", "深蓝色折叠伞，木柄，第二食堂二楼",
                "第二食堂二楼", "黑色长柄雨伞，塑料柄，第二食堂二楼", "第二食堂二楼", PUBLISHER);
        add(list, "N04", false, "证件", "学生证", "学生证，姓名：王五，第三教学楼",
                "第三教学楼", "校园卡，姓名：孙七，第三教学楼", "第三教学楼", PUBLISHER);
        add(list, "N05", false, "电脑", "银色 MacBook Air", "银色 MacBook Air，宇航员贴纸，图书馆四楼",
                "图书馆四楼", "深空灰 iPad Pro，无贴纸，图书馆四楼", "图书馆四楼", PUBLISHER);
        return list;
    }

    private static void add(List<Fixture> list, String id, boolean positive, String category,
                            String lostTitle, String lostDescription, String lostLocation,
                            String foundTitle, String foundLocation, String publisher) {
        // 招领端用同一段文本作为标题与描述：短文本下两者本就同义，避免为凑参数写出不自然的文案。
        list.add(new Fixture(id, positive, category, lostTitle, lostDescription, lostLocation,
                foundTitle, foundTitle, foundLocation, publisher));
    }

    private record Text(String title, String description, String location) {
    }

    private record Fixture(String id, boolean positive, String category,
                           String lostTitle, String lostDescription, String lostLocation,
                           String foundTitle, String foundDescription, String foundLocation,
                           String publisher) {
    }

    private static class PairSample {
        final Fixture fixture;
        final PairSide lost;
        final PairSide found;
        BigDecimal scored;

        PairSample(Fixture fixture, PairSide lost, PairSide found) {
            this.fixture = fixture;
            this.lost = lost;
            this.found = found;
        }
    }

    private static class RankQuery {
        final PairSide lost;
        final List<PairSide> candidates;
        List<BigDecimal> result = List.of();

        RankQuery(PairSide lost, List<PairSide> candidates) {
            this.lost = lost;
            this.candidates = candidates;
        }
    }

    private static class Channel {
        final List<PairSample> pairs = new ArrayList<>();
        final List<RankQuery> rankQueries = new ArrayList<>();
        final Map<String, BigDecimal> scores = new LinkedHashMap<>();
    }

    private static class EmptyProvider implements ObjectProvider<CategoryMapper> {
        @Override
        public CategoryMapper getObject(Object... args) {
            return null;
        }

        @Override
        public CategoryMapper getIfAvailable() {
            return null;
        }

        @Override
        public CategoryMapper getIfUnique() {
            return null;
        }

        @Override
        public CategoryMapper getObject() {
            return null;
        }
    }
}
