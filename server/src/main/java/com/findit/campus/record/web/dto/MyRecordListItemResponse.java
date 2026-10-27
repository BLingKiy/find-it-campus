package com.findit.campus.record.web.dto;

import java.time.LocalDateTime;

import com.findit.campus.common.config.JacksonConfig;
import com.findit.campus.record.domain.MyPairCounts;
import com.findit.campus.record.domain.MyRecordRow;
import com.findit.campus.record.domain.RecordStatus;

import com.fasterxml.jackson.annotation.JsonFormat;

/**
 * 「我的启事」列表项（契约 §4.12）：<b>所有者视角</b>，因此 contact/publisherName/location/title 均为原文，
 * 不经过 {@code MaskingPolicy}；这是本人数据回显，不是跨用户泄露。
 *
 * <p>{@code detailVisible}：{@code PENDING=true}，{@code COMPLETED/CLOSED=false}。
 * COMPLETED 按 A9 字面"所有者详情也 404"，前端据此不渲染详情入口；本类与详情出口共用
 * {@code RecordVisibilityPolicy} 的口径，不会出现"列表能点、点进去 404"。
 *
 * <p>{@code pairs} 数组与 {@code PairCard} 不在 C1 范围（E1）：只给四个计数，
 * 且计数口径逐字对应契约 §4.12 的 pairCount/relatedCount/lowConfidenceCount/matchedPairCount。
 */
public class MyRecordListItemResponse {

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
    private LocalDateTime completedAt;

    private String contact;
    private String publisherName;
    private String pairState;
    private Integer pairCount;
    private Integer relatedCount;
    private Integer lowConfidenceCount;
    private Boolean hasMatchedPair;
    private Integer matchedPairCount;
    private Boolean detailVisible;

    @JsonFormat(pattern = JacksonConfig.DATE_TIME_PATTERN)
    private LocalDateTime createdAt;

    @JsonFormat(pattern = JacksonConfig.DATE_TIME_PATTERN)
    private LocalDateTime updatedAt;

    public static MyRecordListItemResponse of(MyRecordRow row, MyPairCounts counts) {
        MyPairCounts effective = counts == null ? MyPairCounts.EMPTY : counts;
        MyRecordListItemResponse response = new MyRecordListItemResponse();
        response.id = row.getId();
        response.type = row.getType() == null ? null : row.getType().name();
        response.status = row.getStatus() == null ? null : row.getStatus().name();
        response.title = row.getTitle();
        response.categoryId = row.getCategoryId();
        response.categoryName = row.getCategoryName();
        response.location = row.getLocation();
        response.occurredAt = row.getOccurredAt();
        response.completedAt = row.getCompletedAt();
        response.contact = row.getContact();
        response.publisherName = row.getPublisherName();
        response.pairState = row.getPairState();
        response.pairCount = effective.total();
        response.relatedCount = effective.related();
        response.lowConfidenceCount = effective.lowConfidence();
        response.hasMatchedPair = effective.matched() > 0;
        response.matchedPairCount = effective.matched();
        response.detailVisible = row.getStatus() == RecordStatus.PENDING;
        response.createdAt = row.getCreatedAt();
        response.updatedAt = row.getUpdatedAt();
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

    public LocalDateTime getCompletedAt() {
        return completedAt;
    }

    public String getContact() {
        return contact;
    }

    public String getPublisherName() {
        return publisherName;
    }

    public String getPairState() {
        return pairState;
    }

    public Integer getPairCount() {
        return pairCount;
    }

    public Integer getRelatedCount() {
        return relatedCount;
    }

    public Integer getLowConfidenceCount() {
        return lowConfidenceCount;
    }

    public Boolean getHasMatchedPair() {
        return hasMatchedPair;
    }

    public Integer getMatchedPairCount() {
        return matchedPairCount;
    }

    public Boolean getDetailVisible() {
        return detailVisible;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
