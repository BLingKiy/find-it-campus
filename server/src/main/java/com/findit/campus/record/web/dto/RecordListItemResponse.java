package com.findit.campus.record.web.dto;

import java.time.LocalDateTime;

import com.findit.campus.common.config.JacksonConfig;
import com.findit.campus.common.masking.MaskingPolicy;
import com.findit.campus.record.domain.RecordSummary;

import com.fasterxml.jackson.annotation.JsonFormat;

/**
 * 公共列表项出参（契约 §4.1）。
 *
 * <p>刻意不含 {@code description}/{@code contact}/{@code publisherName}：列表一次最多 50 条，
 * 卡片也不展示它们；联系人只在详情给（最小暴露）。因此列表的遮蔽只有两处：
 * {@code title}（自由文本，防御性打码）与 {@code location}（截断）。
 *
 * <p>{@code isMine} 是 v2 新增：本人行不遮蔽标题/地点，前端据它决定是否渲染"管理"入口。
 * 判定依据是 {@code publisher_id}，不是可能重名的展示名。
 *
 * <p>{@link #from(RecordSummary)} 是<b>遮蔽视角</b>默认（fail-closed）；本人视角必须显式传 viewerId。
 */
public class RecordListItemResponse {

    private Long id;
    private String type;
    private String status;
    private String title;
    private Long categoryId;
    private String categoryName;
    private String location;
    /** 展示摘要（类别+关键特征+模糊地点，≤40 字）；历史/未启用闸门的行回退为 null。 */
    private String summary;

    @JsonFormat(pattern = JacksonConfig.DATE_TIME_PATTERN)
    private LocalDateTime occurredAt;

    @JsonFormat(pattern = JacksonConfig.DATE_TIME_PATTERN)
    private LocalDateTime createdAt;

    private Boolean isMine;

    /** 遮蔽视角（匿名/非所有者）。 */
    public static RecordListItemResponse from(RecordSummary summary) {
        return from(summary, null);
    }

    /**
     * 按查看者身份装配本行。
     *
     * @param viewerId 当前会话用户；{@code null} = 匿名
     */
    public static RecordListItemResponse from(RecordSummary summary, Long viewerId) {
        boolean mine = viewerId != null && summary.getPublisherId() != null
                && summary.getPublisherId().equals(viewerId);
        RecordListItemResponse response = new RecordListItemResponse();
        response.id = summary.getId();
        response.type = summary.getType() == null ? null : summary.getType().name();
        response.status = summary.getStatus() == null ? null : summary.getStatus().name();
        response.title = mine ? summary.getTitle() : MaskingPolicy.maskDescription(summary.getTitle());
        response.categoryId = summary.getCategoryId();
        response.categoryName = summary.getCategoryName();
        response.location = mine ? summary.getLocation() : MaskingPolicy.maskLocation(summary.getLocation());
        response.summary = summary.getSummary() == null
                ? null
                : (mine ? summary.getSummary() : MaskingPolicy.maskDescription(summary.getSummary()));
        response.occurredAt = summary.getOccurredAt();
        response.createdAt = summary.getCreatedAt();
        response.isMine = mine;
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

    public String getSummary() {
        return summary;
    }

    public LocalDateTime getOccurredAt() {
        return occurredAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public Boolean getIsMine() {
        return isMine;
    }
}
