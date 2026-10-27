package com.findit.campus.pair.engine;

/**
 * {@code t_pair_score.engine} 的两个取值（DDL CHECK 同值域）。
 *
 * <p>{@code LOCAL_KEYWORD} <b>不是</b>另一个"算法选项"，而是降级标记：它出现就说明这条分数
 * 不是 LLM 产出，前端必须显示「已降级」。因此不允许把它当作正常引擎配置项暴露成开关。
 */
public final class PairEngines {

    public static final String DEEPSEEK = "DEEPSEEK";
    public static final String LOCAL_KEYWORD = "LOCAL_KEYWORD";

    private PairEngines() {
    }
}
