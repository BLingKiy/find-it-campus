package com.findit.campus.record.domain;

/**
 * 记录状态。
 *
 * <p>与 {@code deleted_at} 语义分离（ADR-006）：</p>
 * <ul>
 *   <li>{@code PENDING} 待领 —— 列表默认只看它</li>
 *   <li>{@code CLAIMED} 已领 —— 可展示，不进匹配候选池</li>
 *   <li>{@code CLOSED} 已关闭/下架 —— 可展示，不进匹配候选池</li>
 *   <li>{@code deleted_at != null} —— 删除，一切读接口不可见（这一层在 SQL 里，不是枚举状态）</li>
 * </ul>
 *
 * <p>流转合法性由 3.1 的 {@code RecordStatusPolicy} 实现，这里只定义取值。</p>
 */
public enum RecordStatus {

    PENDING,
    CLAIMED,
    CLOSED,
    ;
}
