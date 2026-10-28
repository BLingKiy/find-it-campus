package com.findit.campus.record.service;

import java.time.LocalDateTime;

import com.findit.campus.record.domain.Record;
import com.findit.campus.record.domain.RecordReviewState;
import com.findit.campus.record.domain.RecordStatus;

/**
 * 记录可见性判定（契约 §4.2 / §9.3 / 补充 #2）。<b>纯函数</b>：不查库，pair 事实由调用方传入。
 *
 * <p>为什么单独成类：这张判定表有 6 个分支，散在 detail / PUT / PATCH / DELETE 四处，
 * 迟早出现"详情 404 了但 PATCH 还能改"这种自相矛盾。纯函数让它能被穷举单测，
 * 也让所有写接口复用同一套"可见 / 不可见 / 可见但非所有者"判定。
 *
 * <p>三档结果：
 * <ul>
 *   <li>{@link DetailAccess#OWNER_PLAINTEXT}：本人 PENDING 详情，明文 + 计数</li>
 *   <li>{@link DetailAccess#MASKED}：可见但非所有者（PENDING 非所有者，或 COMPLETED 的合格对端），
 *       走遮蔽视图，{@code contact=null}</li>
 *   <li>{@link DetailAccess#NOT_FOUND}：按不可见处理 → 404/20001，<b>不泄露存在性</b></li>
 * </ul>
 *
 * <p>三条硬规则（不得改）：
 * <ol>
 *   <li>CLOSED：任何身份 404，<b>含所有者</b>（所有人走 /my 摘要）</li>
 *   <li>COMPLETED 到期：任何身份 404</li>
 *   <li>COMPLETED 未到期：所有者 404（A9 字面），只有合格对端 200（遮蔽）；IGNORED 的 pair
 *       不算合格对端，与"没有 pair"同码同文案</li>
 * </ol>
 */
public final class RecordVisibilityPolicy {

    public enum DetailAccess {
        OWNER_PLAINTEXT,
        MASKED,
        NOT_FOUND
    }

    private RecordVisibilityPolicy() {
    }

    /**
     * 保留期是否已过：{@code completed_at + retentionDays > now} 才算仍在窗口内。
     *
     * <p>边界取「大于」而不是「大于等于」：到期那一刻即不可见，避免"最后一秒"的时钟偏差
     * 让同一请求在不同实例上出现两种结果。
     */
    public static boolean isWithinRetention(LocalDateTime completedAt, int retentionDays, LocalDateTime now) {
        return completedAt != null && completedAt.plusDays(retentionDays).isAfter(now);
    }

    /**
     * 召回谓词（{@code MATCHABLE}，契约 §4.8）：PENDING 恒可；COMPLETED 仅在保留期内；CLOSED 不可。
     *
     * <p>「列表隐藏 ≠ 匹配隐藏」：COMPLETED 在公共列表 0 命中，但仍进候选池 —— 这正是
     * 失主在别处发帖后还能被匹配到的前提。CLOSED 记录则连召回都不该出现。
     */
    public static boolean isMatchable(RecordStatus status, LocalDateTime completedAt,
                                      int retentionDays, LocalDateTime now) {
        if (status == null) {
            return false;
        }
        return switch (status) {
            case PENDING -> true;
            case COMPLETED -> isWithinRetention(completedAt, retentionDays, now);
            case CLOSED -> false;
        };
    }

    /**
     * 详情出口的可见性（按顺序命中，等价于契约 §4.2 的判定伪代码）。
     *
     * @param record                 已确认未逻辑删除的记录
     * @param viewerId               当前会话用户 id；匿名传 {@code null}
     * @param viewerHasQualifyingPair 查看者是否是合格对端（只对 COMPLETED 有意义）
     * @param retentionDays          配置 {@code completed_retention_days}
     * @param now                    当前时间（调用方传入，便于测试固定时钟）
     */
    public static DetailAccess detailAccess(Record record, Long viewerId, boolean viewerHasQualifyingPair,
                                            int retentionDays, LocalDateTime now) {
        if (record == null || record.getStatus() == null) {
            return DetailAccess.NOT_FOUND;
        }
        RecordStatus status = record.getStatus();
        if (status == RecordStatus.CLOSED) {
            return DetailAccess.NOT_FOUND;
        }
        boolean owner = isOwner(record, viewerId);
        // 发布闸门：未通过审核的内容，**非所有者一律 404**（与不存在同码，不泄露"审核中"）。
        // 所有者仍可打开发布/编辑页看到自己的记录与审核态 —— 那不是对外可见，是本人回显。
        if (!owner && record.getReviewState() != RecordReviewState.APPROVED) {
            return DetailAccess.NOT_FOUND;
        }
        if (status == RecordStatus.PENDING) {
            return owner ? DetailAccess.OWNER_PLAINTEXT : DetailAccess.MASKED;
        }
        // COMPLETED
        if (!isWithinRetention(record.getCompletedAt(), retentionDays, now)) {
            return DetailAccess.NOT_FOUND;
        }
        if (owner) {
            return DetailAccess.NOT_FOUND;
        }
        // 匿名永远不是"合格对端"：即使调用方误传 true，也不能让没有身份的人看到 COMPLETED 详情
        return viewerId != null && viewerHasQualifyingPair ? DetailAccess.MASKED : DetailAccess.NOT_FOUND;
    }

    /**
     * 所有权判定：{@code publisher_id == session.userId}。
     *
     * <p>匿名永远不是所有者；{@code publisherId} 为 null 的历史行也永远不匹配
     * （宁可让迁移期数据不可写，也不要让"空等于空"把所有匿名用户变成所有者）。
     */
    public static boolean isOwner(Record record, Long viewerId) {
        return viewerId != null && record != null && record.getPublisherId() != null
                && record.getPublisherId().equals(viewerId);
    }
}
