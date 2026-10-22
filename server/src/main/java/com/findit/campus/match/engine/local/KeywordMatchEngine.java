package com.findit.campus.match.engine.local;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.findit.campus.category.domain.Category;
import com.findit.campus.category.mapper.CategoryMapper;
import com.findit.campus.match.engine.MatchCandidate;
import com.findit.campus.match.engine.MatchCandidateSource;
import com.findit.campus.match.engine.MatchEngine;
import com.findit.campus.match.engine.MatchQuery;

/**
 * 本地关键词匹配实现（{@code LOCAL_KEYWORD}，{@code findit.match.engine=local} 时生效）。
 *
 * <p><b>它只做"候选原文 → 分数"</b>：候选池取数、回表过滤、排序、截断、快照都在
 * {@code MatchService} 里（3.1 已交付），本类不注入 {@code RecordMapper}、不写库、不认识 HTTP。
 *
 * <p><b>算法（可解释、可复现，不依赖外部服务）</b>：
 * <ol>
 *   <li>中文按 unigram + bigram 切分，英文/数字按词切分；虚词 unigram 丢弃，两个虚词组成的 bigram 也丢弃。</li>
 *   <li>权重 = 基础权重 × IDF（IDF 按本次候选池统计），所以"钱包/钥匙/计算器"这类稀有词
 *       天然比"捡到/丢了"这类到处都有的词更有分量，不需要维护停用词大表。</li>
 *   <li>文本分 = 0.78 × 查询词覆盖 + 0.22 × 标题覆盖：覆盖比"绝对词数"更公平，
 *       长描述不会仅因为字多就压过短标题。</li>
 *   <li>地点分：候选 {@code location} 的 bigram 有多少出现在查询里，最多加 0.07。</li>
 *   <li>分类分：{@code t_category.keywords} 是人工维护的类目词表；查询命中某类目词、且候选属于该类目
 *       并且候选正文也含该词时，加 0.25。只加"两边都出现"的情况，避免"查询里有'伞'→把所有衣物类都加分"。
 *       词表只读、进程内缓存一次；词表加载失败只降级为纯文本匹配，不让匹配 500。</li>
 *   <li>同分按 recordId 升序，结果确定；分数四舍五入到 2 位小数，落在 [0,1]。</li>
 * </ol>
 *
 * <p><b>为什么依赖 {@link CategoryMapper}</b>：类目词表是 {@code t_category} 的只读参考数据，
 * 不是候选/业务数据。引擎不直接读 {@code t_record}，也不判断"记录是否可见"。
 * 代价是首次匹配会多一次 8 行小表查询（之后走进程内缓存），换来的是词表只有一份、不会和 DDL/seed 漂移。
 *
 * <p><b>已知边界</b>：同义词（"充电宝"↔"移动电源"）、拼音、错别字命中不了；候选池外的记录召回为 0；
 * 打分成本随候选池线性增长（池上限 500，单次约 O(池大小 × 查询词数)）。
 * 这些是选择"本地、零外部依赖、结果可复现"的代价；需要语义泛化时按 {@code docs/ai-match-module.md} §3.2
 * 换成 external 实现，本类与调用方都不需要改。
 */
public class KeywordMatchEngine implements MatchEngine {

    /** 落 {@code t_match_result.impl}，与 schema 的 CHECK 值域一致。 */
    public static final String IMPL = "LOCAL_KEYWORD";

    private static final Logger log = LoggerFactory.getLogger(KeywordMatchEngine.class);

    /** 基础权重：bigram 是主要语义单元；unigram 只做兜底（同义词/单字物品名）。 */
    private static final double BIGRAM_WEIGHT = 1.0;
    private static final double UNIGRAM_WEIGHT = 0.45;
    private static final double WORD_WEIGHT = 1.4;

    /** 分数构成。四个数相加上限 1.0（文本 0.78 + 标题 0.22 + 地点 0.07 + 分类 0.25 = 1.32，最后 clamp）。 */
    private static final double COVERAGE_WEIGHT = 0.78;
    private static final double TITLE_WEIGHT = 0.22;
    private static final double LOCATION_WEIGHT = 0.07;
    private static final double CATEGORY_WEIGHT = 0.25;

    private static final int REASON_MAX_WORDS = 4;
    private static final int SCORE_SCALE = 2;

    /** 虚词表：只丢 unigram；bigram 只有在两个字符都是虚词时才丢（"钱包"不会被拆坏）。 */
    private static final Set<Character> STOP_CHARS = Set.of(
            '的', '了', '是', '在', '有', '和', '与', '及', '我', '你', '他', '她', '它',
            '吗', '呢', '吧', '啊', '就', '都', '也', '还', '又', '把', '被', '让', '给',
            '对', '从', '到', '个', '一', '位', '条', '只', '张', '件', '串');

    private static final Pattern SEGMENT = Pattern.compile("[a-z0-9]+|[\\u4e00-\\u9fff]+");

    private final CategoryMapper categoryMapper;

    /** 类目词表只读快照。volatile + 双检锁：匹配是并发热点，不能每请求查库。 */
    private volatile Lexicon lexicon;

    public KeywordMatchEngine(CategoryMapper categoryMapper) {
        this.categoryMapper = categoryMapper;
    }

    @Override
    public String impl() {
        return IMPL;
    }

    @Override
    public List<MatchCandidate> match(List<MatchCandidateSource> candidates, MatchQuery query) {
        if (candidates == null || candidates.isEmpty() || query == null) {
            return List.of();
        }
        String queryText = normalize(query.inputText());
        if (queryText.isEmpty() || query.topN() <= 0) {
            return List.of();
        }

        List<Doc> docs = new ArrayList<>(candidates.size());
        for (MatchCandidateSource source : candidates) {
            if (source == null || source.recordId() == null) {
                continue;
            }
            String title = normalize(source.title());
            String description = normalize(source.description());
            docs.add(new Doc(source,
                    union(tokensOf(title), tokensOf(description)),
                    tokensOf(title),
                    tokensOf(normalize(source.location()), false),
                    title + '|' + description));
        }
        if (docs.isEmpty()) {
            return List.of();
        }

        Map<String, Integer> documentFrequency = new HashMap<>();
        for (Doc doc : docs) {
            for (String token : doc.tokens()) {
                documentFrequency.merge(token, 1, Integer::sum);
            }
        }

        Map<String, Double> queryWeights = new HashMap<>();
        double totalWeight = 0;
        for (String token : tokensOf(queryText)) {
            double weight = baseWeight(token) * idf(documentFrequency.getOrDefault(token, 0), docs.size());
            queryWeights.put(token, weight);
            totalWeight += weight;
        }
        if (totalWeight <= 0) {
            return List.of();
        }

        Lexicon currentLexicon = lexicon();
        InferredCategory inferred = inferCategory(queryText, query.categoryId(), currentLexicon);
        BigDecimal minScore = query.minScore() == null ? BigDecimal.ZERO : query.minScore();

        List<Scored> scored = new ArrayList<>(docs.size());
        Set<Long> seen = new HashSet<>();
        for (Doc doc : docs) {
            if (!seen.add(doc.source().recordId())) {
                continue; // 候选池理论上已去重，这里仍兜一次：同一 id 只保留第一次出现
            }
            double matchedWeight = 0;
            List<Hit> hits = new ArrayList<>();
            for (Map.Entry<String, Double> entry : queryWeights.entrySet()) {
                if (doc.tokens().contains(entry.getKey())) {
                    matchedWeight += entry.getValue();
                    hits.add(new Hit(entry.getKey(), entry.getValue()));
                }
            }
            double coverage = matchedWeight / totalWeight;
            double titleCoverage = titleCoverage(doc, queryWeights, totalWeight);
            double locationCoverage = locationCoverage(doc, queryWeights.keySet());
            String categoryName = matchedCategory(inferred, doc);
            double raw = COVERAGE_WEIGHT * coverage
                    + TITLE_WEIGHT * titleCoverage
                    + LOCATION_WEIGHT * locationCoverage
                    + (categoryName == null ? 0 : CATEGORY_WEIGHT);

            BigDecimal score = toScore(raw);
            if (score.compareTo(minScore) < 0) {
                continue;
            }
            scored.add(new Scored(doc.source().recordId(), score, reason(hits, inferred, doc)));
        }

        scored.sort(Comparator.comparing(Scored::score).reversed()
                .thenComparing(Scored::recordId));
        int limit = Math.min(query.topN(), scored.size());
        List<MatchCandidate> result = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            Scored item = scored.get(i);
            result.add(new MatchCandidate(item.recordId(), item.score(), item.reason()));
        }
        return List.copyOf(result);
    }

    // ------------------------------------------------------------------ 打分

    private static double titleCoverage(Doc doc, Map<String, Double> queryWeights, double totalWeight) {
        if (totalWeight <= 0) {
            return 0;
        }
        double matched = 0;
        for (Map.Entry<String, Double> entry : queryWeights.entrySet()) {
            if (doc.titleTokens().contains(entry.getKey())) {
                matched += entry.getValue();
            }
        }
        return matched / totalWeight;
    }

    /**
     * 地点覆盖：只看 bigram，避免"图/书/馆"这类单字把地点分抬得过高。
     * 查询里出现候选地点的多少（候选地点越具体，分母越大）—— 这是"地点证据"的保守下限。
     */
    private static double locationCoverage(Doc doc, Set<String> queryTokens) {
        int total = 0;
        int matched = 0;
        for (String token : doc.locationTokens()) {
            if (!token.startsWith("b:")) {
                continue;
            }
            total++;
            if (queryTokens.contains(token)) {
                matched++;
            }
        }
        return total == 0 ? 0 : (double) matched / total;
    }

    /**
     * 分类分：查询命中的类目词，候选也必须真的含有该词（不是只同属一个类目），才加分。
     * 这样"查询里有'伞' + 候选是衣物类"不会把袜子/羽绒服一起抬上来。
     *
     * @return 命中时返回类目名（写 reason），未命中返回 {@code null}
     */
    private static String matchedCategory(InferredCategory inferred, Doc doc) {
        if (inferred == null || doc.source().categoryId() == null
                || !inferred.category().id().equals(doc.source().categoryId())) {
            return null;
        }
        for (KeywordEntry entry : inferred.entries()) {
            if (doc.normalizedText().contains(entry.normalized())) {
                String name = inferred.category().name();
                return name == null || name.isBlank() ? "分类#" + inferred.category().id() : name;
            }
        }
        return null;
    }

    private static BigDecimal toScore(double raw) {
        double clamped = Math.min(1.0, Math.max(0.0, raw));
        return BigDecimal.valueOf(clamped).setScale(SCORE_SCALE, RoundingMode.HALF_UP);
    }

    private static double baseWeight(String token) {
        if (token.startsWith("w:")) {
            return WORD_WEIGHT;
        }
        if (token.startsWith("b:")) {
            return BIGRAM_WEIGHT;
        }
        return UNIGRAM_WEIGHT;
    }

    /** IDF：df=0 的词权重最高；df 接近池大小则接近 1。+1 平滑避免 ln(0)。 */
    private static double idf(int documentFrequency, int documentCount) {
        return Math.log((documentCount + 1.0) / (documentFrequency + 1.0)) + 1.0;
    }

    // ------------------------------------------------------------------ 分词

    private static Set<String> tokensOf(String normalized) {
        return tokensOf(normalized, true);
    }

    private static Set<String> tokensOf(String normalized, boolean withUnigrams) {
        Set<String> tokens = new HashSet<>();
        if (normalized == null || normalized.isEmpty()) {
            return tokens;
        }
        Matcher matcher = SEGMENT.matcher(normalized);
        while (matcher.find()) {
            String run = matcher.group();
            char first = run.charAt(0);
            if (first < '\u4e00' || first > '\u9fff') {
                tokens.add("w:" + run);
                continue;
            }
            if (withUnigrams) {
                for (int i = 0; i < run.length(); i++) {
                    char ch = run.charAt(i);
                    if (!STOP_CHARS.contains(ch)) {
                        tokens.add("u:" + ch);
                    }
                }
            }
            for (int i = 0; i + 1 < run.length(); i++) {
                char left = run.charAt(i);
                char right = run.charAt(i + 1);
                if (STOP_CHARS.contains(left) && STOP_CHARS.contains(right)) {
                    continue;
                }
                tokens.add("b:" + run.substring(i, i + 2));
            }
        }
        return tokens;
    }

    /** 统一小写、去掉标点与空白，只保留中文/英文/数字。 */
    private static String normalize(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char ch = Character.toLowerCase(text.charAt(i));
            boolean cjk = ch >= '\u4e00' && ch <= '\u9fff';
            boolean latin = ch >= 'a' && ch <= 'z';
            boolean digit = ch >= '0' && ch <= '9';
            if (cjk || latin || digit) {
                builder.append(ch);
            }
        }
        return builder.toString();
    }

    private static Set<String> union(Set<String> left, Set<String> right) {
        Set<String> union = new HashSet<>(left.size() + right.size());
        union.addAll(left);
        union.addAll(right);
        return union;
    }

    // ------------------------------------------------------------------ 类目词表

    private Lexicon lexicon() {
        Lexicon current = lexicon;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (lexicon == null) {
                lexicon = loadLexicon();
            }
            return lexicon;
        }
    }

    /**
     * 一次加载、进程内只读缓存。失败不抛：匹配的核心能力是文本打分，词表只是加权信号；
     * 为了一个可选信号把整个 {@code /api/match} 打成 500 不划算（与 ADR-009"外部不可用明确失败"不同，
     * 这里没有外部服务，只有本库一张参考表）。
     */
    private Lexicon loadLexicon() {
        try {
            Map<Long, CategoryLexicon> byCategory = new HashMap<>();
            for (Category category : categoryMapper.selectAll(true)) {
                if (category == null || category.getId() == null
                        || category.getKeywords() == null || category.getKeywords().isBlank()) {
                    continue;
                }
                List<KeywordEntry> entries = new ArrayList<>();
                for (String rawKeyword : category.getKeywords().trim().split("\\s+")) {
                    String keyword = normalize(rawKeyword);
                    if (!keyword.isEmpty()) {
                        entries.add(new KeywordEntry(keyword, Math.max(1, keyword.length())));
                    }
                }
                if (!entries.isEmpty()) {
                    byCategory.put(category.getId(),
                            new CategoryLexicon(category.getId(), category.getName(), List.copyOf(entries)));
                }
            }
            log.info("本地匹配类目词表已加载: {} 个分类", byCategory.size());
            return new Lexicon(Map.copyOf(byCategory));
        } catch (RuntimeException e) {
            log.warn("本地匹配类目词表加载失败，本次降级为纯文本匹配: {}", e.getMessage());
            return Lexicon.EMPTY;
        }
    }

    /**
     * 推断查询的"主类目"：命中的类目词按字符长度加权，最长/最多的类目胜出（并列取 id 小者，保证可复现）。
     * 调用方显式传了 {@code categoryId} 时只在该类目内找词，不跨类目猜。
     */
    private static InferredCategory inferCategory(String queryText, Long requestedCategoryId, Lexicon lexicon) {
        if (lexicon.byCategory().isEmpty()) {
            return null;
        }
        if (requestedCategoryId != null) {
            CategoryLexicon category = lexicon.byCategory().get(requestedCategoryId);
            if (category == null) {
                return null;
            }
            List<KeywordEntry> matched = matchEntries(queryText, category);
            return matched.isEmpty() ? null : new InferredCategory(category, matched);
        }
        CategoryLexicon best = null;
        List<KeywordEntry> bestEntries = List.of();
        int bestScore = 0;
        for (CategoryLexicon category : lexicon.byCategory().values()) {
            List<KeywordEntry> matched = matchEntries(queryText, category);
            if (matched.isEmpty()) {
                continue;
            }
            int score = matched.stream().mapToInt(KeywordEntry::weight).sum();
            if (score > bestScore || (score == bestScore && best != null && category.id() < best.id())) {
                best = category;
                bestEntries = matched;
                bestScore = score;
            }
        }
        return best == null ? null : new InferredCategory(best, bestEntries);
    }

    private static List<KeywordEntry> matchEntries(String queryText, CategoryLexicon category) {
        List<KeywordEntry> matched = new ArrayList<>();
        for (KeywordEntry entry : category.entries()) {
            if (queryText.contains(entry.normalized())) {
                matched.add(entry);
            }
        }
        return matched;
    }

    // ------------------------------------------------------------------ 内部结构

    private record Doc(MatchCandidateSource source, Set<String> tokens, Set<String> titleTokens,
                       Set<String> locationTokens, String normalizedText) {
    }

    private record Hit(String token, double weight) {
    }

    private record Scored(Long recordId, BigDecimal score, String reason) {
    }

    private record KeywordEntry(String normalized, int weight) {
    }

    private record CategoryLexicon(Long id, String name, List<KeywordEntry> entries) {
    }

    private record InferredCategory(CategoryLexicon category, List<KeywordEntry> entries) {
    }

    private record Lexicon(Map<Long, CategoryLexicon> byCategory) {
        private static final Lexicon EMPTY = new Lexicon(Map.of());
    }

    /**
     * 可解释原因：先放"查询和候选都出现的类目词"（钱包/银行卡/钥匙这类人话），
     * 再按权重补几个正文命中词；单字如果已经被更长的命中词包含（"钱"/"包" ⊂ "钱包"）就跳过。
     * 含虚词的 bigram（"个黑""有银"）只参与打分、不进 reason —— 用户看到的应该是词，不是切片。
     * 前端只展示、不解析（契约 §8.2）。
     */
    private static String reason(List<Hit> hits, InferredCategory inferred, Doc doc) {
        List<String> words = new ArrayList<>(REASON_MAX_WORDS);
        if (inferred != null) {
            for (KeywordEntry entry : inferred.entries()) {
                if (doc.normalizedText().contains(entry.normalized())) {
                    addWord(words, entry.normalized());
                }
            }
        }
        List<Hit> sorted = new ArrayList<>(hits);
        sorted.sort((left, right) -> {
            boolean leftInTitle = doc.titleTokens().contains(left.token());
            boolean rightInTitle = doc.titleTokens().contains(right.token());
            if (leftInTitle != rightInTitle) {
                return leftInTitle ? -1 : 1;
            }
            int byWeight = Double.compare(right.weight(), left.weight());
            return byWeight != 0 ? byWeight : left.token().compareTo(right.token());
        });
        for (Hit hit : sorted) {
            if (!isMeaningful(hit.token())) {
                continue;
            }
            addWord(words, display(hit.token()));
            if (words.size() == REASON_MAX_WORDS) {
                break;
            }
        }
        StringBuilder builder = new StringBuilder();
        if (!words.isEmpty()) {
            builder.append("关键词命中：").append(String.join("、", words));
        }
        if (inferred != null && matchedCategory(inferred, doc) != null) {
            if (builder.length() > 0) {
                builder.append("；");
            }
            String name = inferred.category().name();
            builder.append("分类词表命中：")
                    .append(name == null || name.isBlank() ? "分类#" + inferred.category().id() : name);
        }
        return builder.length() == 0 ? "无关键词命中" : builder.toString();
    }

    private static void addWord(List<String> words, String word) {
        if (word == null || word.isEmpty() || words.size() >= REASON_MAX_WORDS || words.contains(word)) {
            return;
        }
        // 已经被更长的词覆盖（"计算"/"算器" ⊂ "计算器"，"钱"/"包" ⊂ "钱包"）就不重复展示
        if (words.stream().anyMatch(existing -> existing.length() > word.length() && existing.contains(word))) {
            return;
        }
        words.add(word);
    }

    /** 至少两个非虚词字符才算"词"；latin 词与单字物品名（伞/书/笔）保留。 */
    private static boolean isMeaningful(String token) {
        if (!token.startsWith("b:")) {
            return true;
        }
        return !STOP_CHARS.contains(token.charAt(2)) && !STOP_CHARS.contains(token.charAt(3));
    }

    private static String display(String token) {
        int colon = token.indexOf(':');
        return colon < 0 ? token : token.substring(colon + 1);
    }
}
