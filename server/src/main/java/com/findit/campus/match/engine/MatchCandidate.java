package com.findit.campus.match.engine;

import java.math.BigDecimal;

/**
 * 引擎的单个输出：<b>只有三元组</b>（ADR-008）。
 *
 * <p>为什么引擎不直接返回展示对象：title/type/status 属业务侧展示字段，"这条记录是否可见（未删除、未关闭）"
 * 是业务规则。让算法去判断这些，等于把业务不变量复制到每个实现里 —— 换实现就会出现口径不一致。
 * 代价是多一次回表 {@code IN} 查询（候选 ≤ topN，可忽略）。
 *
 * @param recordId 命中的记录 id
 * @param score    相似度 0~1
 * @param reason   可解释的原因字符串（本地实现给命中词，外部实现给算法说明）
 */
public record MatchCandidate(Long recordId, BigDecimal score, String reason) {
}
