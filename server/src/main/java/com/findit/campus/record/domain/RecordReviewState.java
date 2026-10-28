package com.findit.campus.record.domain;

/**
 * 发布闸门的审核态（{@code t_record.review_state}），与业务三态 {@link RecordStatus} <b>正交</b>。
 *
 * <p>为什么不做成第四个业务状态：三态状态机已经被 45 条验收证据、PATCH 流转、COMPLETED 保留期
 * 绑定；把「审核中」塞进 status 会让「PENDING 恒进公共列表」等既有不变量全部改写。
 * 审核只回答一个问题：<b>这条内容是否允许对外可见</b>。
 *
 * <p>状态迁移（只有发布/编辑/审核/人工四条路径写）：
 * <pre>
 *   POST/PUT (mode=ai)  → PENDING ──AI 通过──→ APPROVED
 *                                ├─AI 拒绝──→ REJECTED
 *                                └─连续失败──→ PENDING + manual_required=1（永不上架）
 *   人工审核             → APPROVED / REJECTED
 *   mode=off            → 直接 APPROVED（与 v2.0 行为一致）
 * </pre>
 */
public enum RecordReviewState {
    /** 已提交，等待 AI 或人工审核；对外不可见、不进候选池、不触发打分。 */
    PENDING,
    /** 审核通过；对外可见，进入公共列表/候选池，并触发配对打分。 */
    APPROVED,
    /** 审核拒绝；对外不可见，{@code review_reason} 对本人可见。 */
    REJECTED
}
