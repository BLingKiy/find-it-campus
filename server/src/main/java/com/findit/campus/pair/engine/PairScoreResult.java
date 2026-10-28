package com.findit.campus.pair.engine;

import java.math.BigDecimal;

/**
 * 一次打分的结果（<b>不是</b> {@code t_pair_score} 整行）。
 *
 * <p>为什么把"分数"和"引擎/降级原因"放在同一个值对象里：两者必须一起落库。
 * 如果评分器只返回分数、由调用方补 engine，就会出现"分数是 LLM 的、engine 写成 LOCAL"这种
 * 谁也发现不了的错账；让产出分数的组件同时产出它的来源，是最小成本的防错。
 *
 * <p>{@code score} 为 {@code null} 只出现在「需要降级」的结果上：
 * LLM 通路声称自己不能给分，但<b>不知道</b>本地关键词分是多少 —— 那不是它的职责。
 * 调用方看到 {@code score == null} 就用本地算法补分，保证"有结果、有分数、有降级标记"。
 *
 * @param score            0..1；降级占位时为 {@code null}
 * @param engine           {@link PairEngines#DEEPSEEK} / {@link PairEngines#LOCAL_KEYWORD}
 * @param degradedReason   降级原因；成功时为 {@code null}
 * @param latencyMs        LLM 客户端实测耗时；本地分数为 {@code null}
 * @param promptTokens     usage.prompt_tokens；缺失或本地分为 {@code null}（<b>不估算</b>）
 * @param completionTokens usage.completion_tokens；同上
 * @param reason           模型给出的 ≤200 字依据；不含号码（prompt 已打码）
 */
public record PairScoreResult(
        BigDecimal score,
        String engine,
        String degradedReason,
        Integer latencyMs,
        Integer promptTokens,
        Integer completionTokens,
        String reason) {

    /** LLM 成功：分数合法、usage 可能缺失（缺失写 NULL，不猜）。 */
    public static PairScoreResult llm(BigDecimal score, int latencyMs,
                                      Integer promptTokens, Integer completionTokens, String reason) {
        return new PairScoreResult(score, PairEngines.DEEPSEEK, null, latencyMs,
                promptTokens, completionTokens, reason);
    }

    /** LLM 不可用：只有原因，没有分数 —— 由调用方用本地关键词补分。 */
    public static PairScoreResult degraded(DegradedReason reason, int latencyMs) {
        return new PairScoreResult(null, PairEngines.LOCAL_KEYWORD, reason.name(), latencyMs, null, null, null);
    }

    /** 本地关键词分（正常路径用它补分，也用于单测的降级桩）。 */
    public static PairScoreResult local(BigDecimal score, Integer latencyMs) {
        return new PairScoreResult(score, PairEngines.LOCAL_KEYWORD, null, latencyMs, null, null, null);
    }

    public boolean degraded() {
        return degradedReason != null;
    }

    public boolean hasScore() {
        return score != null;
    }
}
