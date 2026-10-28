package com.findit.campus.record.mapper;

import com.findit.campus.record.domain.RecordSortField;
import com.findit.campus.record.domain.RecordStatus;
import com.findit.campus.record.domain.RecordType;
import com.findit.campus.record.domain.SortDirection;

/**
 * 公共列表查询条件（mapper 入参）。
 *
 * <p>{@code status} 在 service 层被强制归一到 {@code PENDING}（契约 §4.1：公共列表可见集合恒为
 * {@code PENDING}）。请求里的 {@code COMPLETED/CLOSED/ALL} 只是"确认它们不在公共列表"，
 * 不改变结果集 —— 因此这里保留字段，但 SQL 里仍硬写 {@code status='PENDING'}，
 * 不给调用方"传个 status 就能查到终态记录"的口子。
 *
 * <p>{@code sortField}/{@code sortDirection} 存的是<b>枚举名而非请求原文</b>（ADR-014）：
 * XML 的 {@code ORDER BY} 只能是硬编码字面量，请求字符串永远进不到 SQL 文本里。
 */
public record RecordCondition(
        RecordType type,
        RecordStatus status,
        Long categoryId,
        String keywordPattern,
        String sortField,
        String sortDirection) {

    /**
     * 唯一构造入口：在此完成 keyword 的 LIKE 转义，调用方不必知道转义字符是什么。
     *
     * @param keyword 用户输入的原始关键词（已 trim，可空）
     */
    public static RecordCondition of(RecordType type, RecordStatus status, Long categoryId, String keyword,
                                     RecordSortField sortField, SortDirection sortDirection) {
        return new RecordCondition(type, status, categoryId, likePattern(keyword), sortField.name(), sortDirection.name());
    }

    /**
     * LIKE 通配符转义（契约 §3.1：{@code %}/{@code _}/{@code \} 需转义后参与 LIKE，否则用户能拿
     * 一个 {@code %} 把全表查出来，或拿 {@code _} 做单字通配）。
     *
     * <p>转义字符选 {@code /} 而不是反斜杠：MySQL 默认 {@code NO_BACKSLASH_ESCAPES} 关闭，
     * 字符串字面量里的 {@code \} 本身还要再转义一层，两层转义正是这类 bug 的温床。
     */
    private static String likePattern(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return null;
        }
        String escaped = keyword.replace("/", "//").replace("%", "/%").replace("_", "/_");
        return "%" + escaped + "%";
    }
}
