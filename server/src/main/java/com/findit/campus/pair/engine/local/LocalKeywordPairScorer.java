package com.findit.campus.pair.engine.local;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import com.findit.campus.category.mapper.CategoryMapper;
import com.findit.campus.match.engine.MatchCandidate;
import com.findit.campus.match.engine.MatchCandidateSource;
import com.findit.campus.match.engine.MatchQuery;
import com.findit.campus.match.engine.local.KeywordMatchEngine;
import com.findit.campus.pair.domain.PairSide;
import com.findit.campus.pair.engine.PairScoreCommand;
import com.findit.campus.pair.engine.PairScoreResult;
import com.findit.campus.pair.engine.PairScorer;

/**
 * 降级用本地关键词打分器：<b>复用 v1 {@link KeywordMatchEngine} 的算法</b>，不另写一套。
 *
 * <p><b>为什么直接 new 一个 {@code KeywordMatchEngine} 而不是注入 {@code MatchEngine} bean</b>：
 * {@code findit.match.engine=external} 时容器里唯一的 {@code MatchEngine} 是外部 embedding 实现，
 * 拿它当兜底就等于"LLM 挂了再用另一个外部服务"，兜底本身也会跟着挂，而且会把
 * {@code engine} 写成 {@code EXTERNAL_EMBEDDING} —— 与「降级 = LOCAL_KEYWORD」的口径冲突。
 * 兜底必须是本地、零依赖、确定性的算法，因此这里直接持有本地实现。代价是：
 * 本地算法改签名时这里要跟着改（一个类，可接受）。
 *
 * <p>打分为<b>双向平均</b>：A→B 与 B→A 各算一次取均值。关键词覆盖本身是非对称的
 * （长描述覆盖短描述容易，反之难），单方向会给"谁是查询"引入偏差；两次内存打分的成本
 * 可以忽略，换来的是同一对记录无论从哪端触发都得到同一个分数（幂等验收要求"连跑两次结果一致"）。
 *
 * <p>它<b>永不抛异常</b>：本地算法唯一的失败点是类目词表查询，而 {@code KeywordMatchEngine}
 * 已经把词表失败降级为空表。真出现运行时异常时返回 0 分而不是让整条链路 500 ——
 * 降级的定义是"有结果地失败"，不是"换个地方崩"。
 */
@Component
public class LocalKeywordPairScorer implements PairScorer {

    private final ObjectProvider<CategoryMapper> categoryMapperProvider;
    private volatile KeywordMatchEngine engine;

    public LocalKeywordPairScorer(ObjectProvider<CategoryMapper> categoryMapperProvider) {
        this.categoryMapperProvider = categoryMapperProvider;
    }

    @Override
    public PairScoreResult score(PairScoreCommand command) {
        long startedAt = System.nanoTime();
        try {
            BigDecimal forward = topScore(command.lost(), List.of(command.found()));
            BigDecimal backward = topScore(command.found(), List.of(command.lost()));
            BigDecimal averaged = forward.add(backward)
                    .divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
            int latencyMs = (int) ((System.nanoTime() - startedAt) / 1_000_000L);
            return PairScoreResult.local(averaged, latencyMs);
        } catch (RuntimeException e) {
            int latencyMs = (int) ((System.nanoTime() - startedAt) / 1_000_000L);
            return PairScoreResult.local(BigDecimal.ZERO.setScale(2), latencyMs);
        }
    }

    /**
     * 召回前的预排序：一次调用给<b>整池</b>候选打分并按降序返回。
     *
     * <p>为什么不在召回阶段逐对调用 {@link #score}：逐对调用会让 IDF 退化到单文档，
     * 且 O(池大小) 次调用拖慢召回。整池一次打分既便宜，又让"稀有词更有分量"这个
     * v1 的关键性质在召回阶段生效。
     *
     * @return 按分数降序的候选（含 recordId 与分数）；输入为空时返回空列表
     */
    public List<MatchCandidate> rank(PairSide query, List<PairSide> candidates) {
        if (query == null || candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        List<MatchCandidateSource> sources = new ArrayList<>(candidates.size());
        for (PairSide candidate : candidates) {
            sources.add(toSource(candidate));
        }
        MatchQuery matchQuery = new MatchQuery(queryText(query), query.type(), null, candidates.size(),
                BigDecimal.ZERO);
        List<MatchCandidate> ranked = engine().match(sources, matchQuery);
        return ranked == null ? List.of() : ranked;
    }

    private BigDecimal topScore(PairSide query, List<PairSide> candidates) {
        List<MatchCandidate> ranked = rank(query, candidates);
        return ranked.isEmpty() ? BigDecimal.ZERO.setScale(2) : ranked.get(0).score();
    }

    private KeywordMatchEngine engine() {
        KeywordMatchEngine current = engine;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (engine == null) {
                CategoryMapper mapper = categoryMapperProvider.getIfAvailable();
                if (mapper == null) {
                    // 没有类目表也不该阻断降级打分：关键词引擎对空词表是安全的
                    mapper = new EmptyCategoryMapper();
                }
                engine = new KeywordMatchEngine(mapper);
            }
            return engine;
        }
    }

    private static MatchCandidateSource toSource(PairSide side) {
        return new MatchCandidateSource(side.recordId(), side.title(), side.description(),
                side.categoryId(), side.location());
    }

    /** 查询文本 = 标题 + 描述 + 地点：三段都是 LLM 打分依据，关键词阶段不该只看标题。 */
    private static String queryText(PairSide side) {
        StringBuilder builder = new StringBuilder();
        append(builder, side.title());
        append(builder, side.description());
        append(builder, side.location());
        return builder.toString();
    }

    private static void append(StringBuilder builder, String part) {
        if (part != null && !part.isBlank()) {
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(part.trim());
        }
    }
}
