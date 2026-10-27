package com.findit.campus.record.mapper;

import com.findit.campus.record.domain.RecordSortField;
import com.findit.campus.record.domain.RecordStatus;
import com.findit.campus.record.domain.RecordType;
import com.findit.campus.record.domain.SortDirection;

/**
 * 「我的启事」查询条件（契约 §4.12）。
 *
 * <p>与 {@link RecordCondition} 分开而不是复用：公共列表的 {@code status} 恒 PENDING，
 * my 的 {@code status} 是可选的（null = 三态全查）。两者共用同一个 record 会让
 * "到底哪条 SQL 会查终态"变成一个必须读 XML 才能回答的问题。
 *
 * @param publisherId 会话用户 id，由 service 从 session 取，<b>不接受请求参数</b>
 * @param status      null = ALL（三态都返回）
 */
public record MyRecordCondition(
        Long publisherId,
        RecordType type,
        RecordStatus status,
        String sortField,
        String sortDirection) {

    public static MyRecordCondition of(Long publisherId, RecordType type, RecordStatus status,
                                       RecordSortField sortField, SortDirection sortDirection) {
        return new MyRecordCondition(publisherId, type, status, sortField.name(), sortDirection.name());
    }
}
