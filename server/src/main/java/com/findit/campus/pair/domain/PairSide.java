package com.findit.campus.pair.domain;

import java.time.LocalDateTime;

import com.findit.campus.record.domain.RecordStatus;
import com.findit.campus.record.domain.RecordType;

/**
 * 打分时对<b>一端</b>记录的只读快照（LOST 端或 FOUND 端）。
 *
 * <p>为什么不让评分器直接吃 {@code Record} 实体：实体带着 {@code contact}、
 * {@code publisherName}、{@code deletedAt} 等与打分无关、却<b>不该出现在 prompt 里</b>的字段。
 * 用一个只含"可进 prompt 的字段"的类型，compiler 就替我们把「号码绝不能进 prompt」这条铁律
 * 变成了类型约束 —— 拿不到，就写不进去。
 *
 * <p>{@code categoryName} 与 {@code occurredAt} 只为 prompt 可读性存在；
 * {@code publisherId} 给通知用（谁该收到这一对的通知），不进 prompt。
 */
public record PairSide(
        Long recordId,
        RecordType type,
        RecordStatus status,
        LocalDateTime completedAt,
        String title,
        String description,
        String location,
        Long categoryId,
        String categoryName,
        LocalDateTime occurredAt,
        Long publisherId) {
}
