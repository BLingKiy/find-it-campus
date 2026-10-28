package com.findit.campus.review.web.dto;

import java.time.LocalDateTime;

import com.findit.campus.record.domain.Record;

/**
 * 人工审核队列的一项。只出**展示文本**与审核元数据；`raw_*`/`match_*` 一律不进运维响应。
 */
public record ReviewQueueItem(
        Long id,
        String type,
        String title,
        String summary,
        String location,
        String reviewState,
        Boolean manualRequired,
        String reviewReason,
        Integer reviewAttempts,
        LocalDateTime createdAt) {

    public static ReviewQueueItem of(Record record) {
        return new ReviewQueueItem(record.getId(),
                record.getType() == null ? null : record.getType().name(),
                record.getTitle(), record.getSummary(), record.getLocation(),
                record.getReviewState() == null ? null : record.getReviewState().name(),
                Boolean.TRUE.equals(record.getReviewManualRequired()),
                record.getReviewReason(), record.getReviewAttempts(), record.getCreatedAt());
    }
}
