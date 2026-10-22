package com.findit.campus.record.web.dto;

import java.time.LocalDateTime;

import com.findit.campus.common.config.JacksonConfig;
import com.findit.campus.record.domain.RecordSummary;

import com.fasterxml.jackson.annotation.JsonFormat;

/**
 * 列表项出参（契约 §3.2）。
 *
 * <p>刻意不含 {@code description}/{@code contact}/{@code publisherName}：列表一次最多 50 条，
 * 卡片也不展示它们；联系人只在详情给（最小暴露，契约 §3.2 末段）。
 *
 * <p>时间字段统一 {@code @JsonFormat}：{@code LocalDateTime} 默认走 ISO-8601（带 {@code T}），
 * 与契约 {@code yyyy-MM-dd HH:mm:ss} 不一致，前端要么自己转、要么出现时区漂移。
 * 常量取自 {@link JacksonConfig#DATE_TIME_PATTERN}，格式只有一处定义。
 */
public class RecordListItemResponse {

    private Long id;
    private String type;
    private String status;
    private String title;
    private Long categoryId;
    private String categoryName;
    private String location;

    @JsonFormat(pattern = JacksonConfig.DATE_TIME_PATTERN)
    private LocalDateTime occurredAt;

    @JsonFormat(pattern = JacksonConfig.DATE_TIME_PATTERN)
    private LocalDateTime createdAt;

    public static RecordListItemResponse from(RecordSummary summary) {
        RecordListItemResponse response = new RecordListItemResponse();
        response.id = summary.getId();
        response.type = summary.getType() == null ? null : summary.getType().name();
        response.status = summary.getStatus() == null ? null : summary.getStatus().name();
        response.title = summary.getTitle();
        response.categoryId = summary.getCategoryId();
        response.categoryName = summary.getCategoryName();
        response.location = summary.getLocation();
        response.occurredAt = summary.getOccurredAt();
        response.createdAt = summary.getCreatedAt();
        return response;
    }

    public Long getId() {
        return id;
    }

    public String getType() {
        return type;
    }

    public String getStatus() {
        return status;
    }

    public String getTitle() {
        return title;
    }

    public Long getCategoryId() {
        return categoryId;
    }

    public String getCategoryName() {
        return categoryName;
    }

    public String getLocation() {
        return location;
    }

    public LocalDateTime getOccurredAt() {
        return occurredAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
