package com.findit.campus.record.domain;

import com.findit.campus.common.api.ErrorCode;
import com.findit.campus.common.error.BizException;

/**
 * 排序方向白名单，与 {@link RecordSortField} 同一套理由（ADR-014）：请求值只用于查表，
 * 进 SQL 的是这里的枚举名，由 XML 的 {@code <choose>} 翻译成 {@code ASC}/{@code DESC} 字面量。
 */
public enum SortDirection {

    ASC,
    DESC,
    ;

    /** 契约默认值：{@code desc}（最新发布的在前）。 */
    public static SortDirection parse(String apiName) {
        if (apiName == null || apiName.isBlank()) {
            return DESC;
        }
        for (SortDirection direction : values()) {
            if (direction.name().equalsIgnoreCase(apiName)) {
                return direction;
            }
        }
        throw BizException.of(ErrorCode.VALIDATION_FAILED, "sortOrder 取值非法: " + apiName + "，可选 asc / desc");
    }
}
