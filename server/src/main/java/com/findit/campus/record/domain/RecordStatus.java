package com.findit.campus.record.domain;

/**
 * 记录状态（v2 三态，契约 §1 / 架构 §9.2）。
 *
 * <p>与 {@code deleted_at} 语义分离（ADR-006）：
 * <ul>
 *   <li>{@code PENDING} 正常发布 —— 公共列表唯一可见的状态，参与召回</li>
 *   <li>{@code COMPLETED} 本人手动标记完成 —— 公共列表 0 命中；保留期内仍参与召回；
 *       详情只对「本人启事与它有合格 pair」的查看者开放（遮蔽视图），其余身份（含所有者）404</li>
 *   <li>{@code CLOSED} 本人手动关闭 —— 不召回、不显示、任何身份详情 404；其 pair 读时失效</li>
 *   <li>{@code deleted_at != null} 逻辑删除 —— 一切读接口不可见（这一层在 SQL 里，不是枚举状态）</li>
 * </ul>
 *
 * <p><b>{@code CLAIMED} 已作废</b>：v2 的「已完成」= COMPLETED、「已关闭」= CLOSED。
 * 收到 CLAIMED 一律按参数非法（10001）处理，不再静默映射 —— 静默映射会让旧前端以为功能还在。
 *
 * <p>流转合法性只由 {@code RecordStatusPolicy} 判定；{@code COMPLETED} 的前置（有效 MATCHED pair）
 * 由 service 在更新前用 pair facts 校验。枚举本身只定义取值。
 */
public enum RecordStatus {

    PENDING,
    COMPLETED,
    CLOSED,
    ;
}
