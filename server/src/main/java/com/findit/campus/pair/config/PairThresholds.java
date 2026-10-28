package com.findit.campus.pair.config;

import java.math.BigDecimal;

/**
 * 一套已解析的配对阈值：某个打分引擎对应的四个业务口径。
 *
 * <p>为什么要这个对象：`findit.pair.*` 从「全局一套」变成「默认一套 + 按引擎覆盖」后，
 * 每个调用点都必须回答同一个问题——<b>这对 pair 的分数是哪套尺度</b>。
 * 把四个值绑成一个不可变对象，调用方就不会出现「direct 用了引擎 A、unlock 用了引擎 B」
 * 这种混搭；三档判定与卡片过滤也因此可以只传一个参数。
 *
 * <p>{@code engine} 只用于日志与错误信息（{@code DEFAULT} / {@code LOCAL_KEYWORD} / {@code DEEPSEEK}），
 * 不参与比较。
 *
 * @param engine           这套阈值所属引擎（{@code DEFAULT} 表示默认/语义引擎那套）
 * @param direct           ≥ 此分直接可看联系方式（DIRECT 档下界）
 * @param unlock           卡片展示与可申请解锁的下界（AUTHORIZABLE 档下界）
 * @param notifyThreshold  写通知的下界（必须 ≥ unlock）
 * @param completedVisible 非所有者查看 COMPLETED 详情的分数下界
 */
public record PairThresholds(String engine,
                             BigDecimal direct,
                             BigDecimal unlock,
                             BigDecimal notifyThreshold,
                             BigDecimal completedVisible) {

    public static final String DEFAULT_ENGINE = "DEFAULT";

    public PairThresholds {
        if (direct == null || unlock == null || notifyThreshold == null || completedVisible == null) {
            throw new IllegalArgumentException("PairThresholds 的四个阈值都不得为空");
        }
    }
}
