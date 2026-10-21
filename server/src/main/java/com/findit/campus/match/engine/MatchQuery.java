package com.findit.campus.match.engine;

import com.findit.campus.record.domain.RecordType;

import java.math.BigDecimal;

/**
 * 一次匹配查询的输入（引擎侧视图，不含 HTTP 概念）。
 *
 * <p>为什么单独有一个它而不是直接用 web 层的 {@code MatchRequest}：web 层 DTO 会随页面需求变（字段名、默认值），
 * 引擎入参不该被前端表单牵着改 —— 这一层薄转换换来的是引擎可以独立测试、也可以被非 HTTP 入口复用。
 *
 * @param inputText   待匹配文本（已由 service 层校验非空白）
 * @param inputType   要找的记录类型：找 LOST（失主在找）还是 FOUND（招领）
 * @param categoryId  分类加权/过滤参数，可空
 * @param topN        返回候选上限
 * @param minScore    分数阈值 0~1
 */
public record MatchQuery(
        String inputText,
        RecordType inputType,
        Long categoryId,
        int topN,
        BigDecimal minScore) {
}
