package com.findit.campus.record.domain;

import java.time.LocalDateTime;

/**
 * 「我的启事」列表的读模型（契约 §4.12）：所有者视角，需要 {@code contact}/{@code publisherName}
 * 与 {@code completedAt}/{@code pairState}，这些字段公共列表读模型故意不带。
 *
 * <p>为什么不复用 {@link Record}：{@code description} 是 TEXT、体积最大，而 my 列表不返回描述；
 * 单列一个读模型让 SQL 的 SELECT 列清单自己说明"这份数据给谁看、给哪些字段"。
 */
public class MyRecordRow {

    private Long id;
    private RecordType type;
    private RecordStatus status;
    private LocalDateTime completedAt;
    private String title;
    private Long categoryId;
    private String categoryName;
    private String location;
    private LocalDateTime occurredAt;
    private String contact;
    private String publisherName;
    private String pairState;
    private RecordReviewState reviewState;
    private String reviewReason;
    private Boolean reviewManualRequired;
    private LocalDateTime reviewedAt;
    private String summary;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public RecordType getType() {
        return type;
    }

    public void setType(RecordType type) {
        this.type = type;
    }

    public RecordStatus getStatus() {
        return status;
    }

    public void setStatus(RecordStatus status) {
        this.status = status;
    }

    public LocalDateTime getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(LocalDateTime completedAt) {
        this.completedAt = completedAt;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public Long getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(Long categoryId) {
        this.categoryId = categoryId;
    }

    public String getCategoryName() {
        return categoryName;
    }

    public void setCategoryName(String categoryName) {
        this.categoryName = categoryName;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public LocalDateTime getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(LocalDateTime occurredAt) {
        this.occurredAt = occurredAt;
    }

    public String getContact() {
        return contact;
    }

    public void setContact(String contact) {
        this.contact = contact;
    }

    public String getPublisherName() {
        return publisherName;
    }

    public void setPublisherName(String publisherName) {
        this.publisherName = publisherName;
    }

    public String getPairState() {
        return pairState;
    }

    public void setPairState(String pairState) {
        this.pairState = pairState;
    }

    public RecordReviewState getReviewState() {
        return reviewState;
    }

    public void setReviewState(RecordReviewState reviewState) {
        this.reviewState = reviewState;
    }

    public String getReviewReason() {
        return reviewReason;
    }

    public void setReviewReason(String reviewReason) {
        this.reviewReason = reviewReason;
    }

    public Boolean getReviewManualRequired() {
        return reviewManualRequired;
    }

    public void setReviewManualRequired(Boolean reviewManualRequired) {
        this.reviewManualRequired = reviewManualRequired;
    }

    public LocalDateTime getReviewedAt() {
        return reviewedAt;
    }

    public void setReviewedAt(LocalDateTime reviewedAt) {
        this.reviewedAt = reviewedAt;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
