package com.findit.campus.record.domain;

import com.findit.campus.common.error.BizException;
import com.findit.campus.common.api.ErrorCode;

/**
 * 排序字段白名单（ADR-014：{@code ORDER BY} 的列名只能来自这里，绝不来自请求字符串）。
 *
 * <p>它挡住的是本项目唯一现实的 SQL 注入面：MyBatis 里 {@code ORDER BY ${col}} 无法用
 * {@code #{}} 参数化，若把请求值直接拼进去就是 OWASP Top-10 级别的口子。
 * 这里的 {@code dbColumn} 是<b>硬编码常量</b>，请求值只用来在枚举里做一次查表，查不到就 10001。
 */
public enum RecordSortField {

    CREATED_AT("createdAt", "created_at"),
    OCCURRED_AT("occurredAt", "occurred_at"),
    ;

    private final String apiName;
    private final String dbColumn;

    RecordSortField(String apiName, String dbColumn) {
        this.apiName = apiName;
        this.dbColumn = dbColumn;
    }

    /** 契约 {@code sortBy} 的取值（camelCase）。 */
    public String apiName() {
        return apiName;
    }

    /** 映射到 SQL 里的实名列名（snake_case），只给 XML 的 {@code <choose>} 分支用。 */
    public String dbColumn() {
        return dbColumn;
    }

    /**
     * 从请求值解析；未知值 → {@code 10001}。
     *
     * <p>{@code null}/空白按契约默认值 {@code createdAt} 处理（默认值是契约的一部分，不是"校验失败"）。
     */
    public static RecordSortField parse(String apiName) {
        if (apiName == null || apiName.isBlank()) {
            return CREATED_AT;
        }
        for (RecordSortField field : values()) {
            if (field.apiName.equals(apiName)) {
                return field;
            }
        }
        throw BizException.of(ErrorCode.VALIDATION_FAILED,
                "sortBy 取值非法: " + apiName + "，可选 " + CREATED_AT.apiName + " / " + OCCURRED_AT.apiName);
    }
}
