package com.findit.campus.record.web.dto;

import java.time.LocalDateTime;
import java.util.List;

import com.findit.campus.common.config.JacksonConfig;
import com.findit.campus.record.domain.Record;
import com.findit.campus.record.domain.RecordStatus;
import com.findit.campus.record.service.RecordStatusPolicy;

import com.fasterxml.jackson.annotation.JsonFormat;

/**
 * 详情出参（契约 §4）。也是 {@code POST}/{@code PUT} 的返回体（契约 §5.2 / §6：与详情同构）。
 *
 * <p>{@code allowedStatuses} 由 {@link RecordStatusPolicy} 给出而不是前端自己推：
 * 状态机放后端，"改流转规则要前后端一起改"这件事就不存在了（契约 §4 注）。
 * 空数组 ⇒ 前端不渲染状态操作按钮（终态）。
 */
public class RecordDetailResponse {

    private Long id;
    private String type;
    private String status;
    private String title;
    private String description;
    private Long categoryId;
    private String categoryName;
    private String location;

    @JsonFormat(pattern = JacksonConfig.DATE_TIME_PATTERN)
    private LocalDateTime occurredAt;

    private String contact;
    private String publisherName;
    private List<String> allowedStatuses;

    @JsonFormat(pattern = JacksonConfig.DATE_TIME_PATTERN)
    private LocalDateTime createdAt;

    @JsonFormat(pattern = JacksonConfig.DATE_TIME_PATTERN)
    private LocalDateTime updatedAt;

    public static RecordDetailResponse from(Record record) {
        RecordDetailResponse response = new RecordDetailResponse();
        response.id = record.getId();
        response.type = record.getType() == null ? null : record.getType().name();
        response.status = record.getStatus() == null ? null : record.getStatus().name();
        response.title = record.getTitle();
        response.description = record.getDescription();
        response.categoryId = record.getCategoryId();
        response.categoryName = record.getCategoryName();
        response.location = record.getLocation();
        response.occurredAt = record.getOccurredAt();
        response.contact = record.getContact();
        response.publisherName = record.getPublisherName();
        response.allowedStatuses = allowedStatusesOf(record.getStatus());
        response.createdAt = record.getCreatedAt();
        response.updatedAt = record.getUpdatedAt();
        return response;
    }

    /** 终态返回空数组（不是 null），前端可以直接 {@code .length} 判断。 */
    private static List<String> allowedStatusesOf(RecordStatus status) {
        if (status == null) {
            return List.of();
        }
        return RecordStatusPolicy.allowedNext(status).stream().map(RecordStatus::name).toList();
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

    public String getDescription() {
        return description;
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

    public String getContact() {
        return contact;
    }

    public String getPublisherName() {
        return publisherName;
    }

    public List<String> getAllowedStatuses() {
        return allowedStatuses;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
