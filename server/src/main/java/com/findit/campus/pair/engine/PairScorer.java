package com.findit.campus.pair.engine;

/**
 * 打分器：把"一对记录"变成"一个分数 + 来源"。
 *
 * <p>两个实现的分工刻意不对称：
 * <ul>
 *   <li>{@code DeepSeekPairScorer} 会<b>尝试</b>调外部 LLM；失败时返回
 *       {@link PairScoreResult#degraded}（分数为 {@code null}），把"补本地分"的决定权交给调用方。</li>
 *   <li>{@code LocalKeywordPairScorer} 只做本地算法，<b>永不失败</b>（没有网络、没有外部状态）。</li>
 * </ul>
 *
 * <p>为什么不把一个"会自动降级的评分器"做成一个实现：那样调用方就无法分别统计
 * 「LLM 调用次数」与「降级次数」，而 D1 的验收要求这两个数可证明（幂等 = 没重复调用，
 * 降级 = 明确标注）。拆成两个纯函数，调用方才能把两件事分开记账。
 */
public interface PairScorer {

    /**
     * @return 非 {@code null}；降级时 {@link PairScoreResult#score()} 为 {@code null}
     * @throws RuntimeException 只允许"不可恢复的编程错误"（如 JSON 库坏了）；
     *                          网络/协议问题必须转换成 {@link PairScoreResult#degraded}
     */
    PairScoreResult score(PairScoreCommand command);
}
