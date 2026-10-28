package com.findit.campus.record.domain;

import java.time.LocalDateTime;

/**
 * {@code t_record} 实体：字段与列一一对应（{@code map-underscore-to-camel-case} 桥接 snake_case）。
 *
 * <p>唯一的例外是 {@link #getCategoryName()}：它<b>不是</b> {@code t_record} 的列，只在带
 * {@code LEFT JOIN t_category} 的读查询里被填充（详情与列表都要展示分类名，回表一次比让上层再查一次便宜）。
 * 写路径（insert/update）的 SQL 显式列列名，因此它不会被误写进库；单表读出来时它恒为 {@code null}。
 *
 * <p>不承载任何校验注解（校验在 {@code web/dto}），也不直接出现在响应体里（出 web 边界前转 Response DTO）。
 */
public class Record {

    private Long id;
    private RecordType type;
    private RecordStatus status;
    private LocalDateTime completedAt;
    private String title;
    private String description;
    private Long categoryId;
    private String categoryName;
    private String location;
    private LocalDateTime occurredAt;
    private String contact;
    private String publisherName;
    private Long publisherId;
    private String pairState;

    // ---- 发布闸门（ZHYYKFX-85）：审核态与双通道文本 ----
    private RecordReviewState reviewState;
    private LocalDateTime reviewedAt;
    private String reviewReason;
    private String reviewEngine;
    private String reviewModel;
    private String reviewPromptVersion;
    private Integer reviewLatencyMs;
    private Integer reviewTokens;
    private Integer reviewAttempts;
    private Boolean reviewManualRequired;
    private String rawTitle;
    private String rawDescription;
    private String rawLocation;
    private String matchTitle;
    private String matchDescription;
    private String matchLocation;
    private String summary;

    private LocalDateTime deletedAt;
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

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Long getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(Long categoryId) {
        this.categoryId = categoryId;
    }

    /** 非列字段，仅 JOIN 读查询填充，见类注释。 */
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

    /** 发布者；应用层新建必填。所有权判定唯一依据（不是 {@code publisherName}，那是可改的展示名）。 */
    public Long getPublisherId() {
        return publisherId;
    }

    public void setPublisherId(Long publisherId) {
        this.publisherId = publisherId;
    }

    /** 标记完成的时间；{@code COMPLETED ⇔ 非空}，同时是保留期的起点。 */
    public LocalDateTime getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(LocalDateTime completedAt) {
        this.completedAt = completedAt;
    }

    /** 系统内部状态（PENDING/SCORING/READY/DEGRADED），与三态 {@code status} 无联动。 */
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

    public LocalDateTime getReviewedAt() {
        return reviewedAt;
    }

    public void setReviewedAt(LocalDateTime reviewedAt) {
        this.reviewedAt = reviewedAt;
    }

    public String getReviewReason() {
        return reviewReason;
    }

    public void setReviewReason(String reviewReason) {
        this.reviewReason = reviewReason;
    }

    public String getReviewEngine() {
        return reviewEngine;
    }

    public void setReviewEngine(String reviewEngine) {
        this.reviewEngine = reviewEngine;
    }

    public String getReviewModel() {
        return reviewModel;
    }

    public void setReviewModel(String reviewModel) {
        this.reviewModel = reviewModel;
    }

    public String getReviewPromptVersion() {
        return reviewPromptVersion;
    }

    public void setReviewPromptVersion(String reviewPromptVersion) {
        this.reviewPromptVersion = reviewPromptVersion;
    }

    public Integer getReviewLatencyMs() {
        return reviewLatencyMs;
    }

    public void setReviewLatencyMs(Integer reviewLatencyMs) {
        this.reviewLatencyMs = reviewLatencyMs;
    }

    public Integer getReviewTokens() {
        return reviewTokens;
    }

    public void setReviewTokens(Integer reviewTokens) {
        this.reviewTokens = reviewTokens;
    }

    public Integer getReviewAttempts() {
        return reviewAttempts;
    }

    public void setReviewAttempts(Integer reviewAttempts) {
        this.reviewAttempts = reviewAttempts;
    }

    public Boolean getReviewManualRequired() {
        return reviewManualRequired;
    }

    public void setReviewManualRequired(Boolean reviewManualRequired) {
        this.reviewManualRequired = reviewManualRequired;
    }

    public String getRawTitle() {
        return rawTitle;
    }

    public void setRawTitle(String rawTitle) {
        this.rawTitle = rawTitle;
    }

    public String getRawDescription() {
        return rawDescription;
    }

    public void setRawDescription(String rawDescription) {
        this.rawDescription = rawDescription;
    }

    public String getRawLocation() {
        return rawLocation;
    }

    public void setRawLocation(String rawLocation) {
        this.rawLocation = rawLocation;
    }

    public String getMatchTitle() {
        return matchTitle;
    }

    public void setMatchTitle(String matchTitle) {
        this.matchTitle = matchTitle;
    }

    public String getMatchDescription() {
        return matchDescription;
    }

    public void setMatchDescription(String matchDescription) {
        this.matchDescription = matchDescription;
    }

    public String getMatchLocation() {
        return matchLocation;
    }

    public void setMatchLocation(String matchLocation) {
        this.matchLocation = matchLocation;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    /** 对外可见的唯一 Java 侧判据；SQL 侧同义谓词是 {@code t_record.is_visible}。 */
    public boolean isApproved() {
        return reviewState == RecordReviewState.APPROVED;
    }

    /**
     * 比对通道取值：新行用 {@code match_*}，v2.0 历史行/seed/mode=off 回退到展示文本。
     *
     * <p>回退不是"偷懒"：历史行没有 match 列，若不回退就会让所有老记录从候选池里消失。
     * 回退值在进 prompt 前仍会过 {@code IdentityScrubber}，所以手机号不会因回退而外泄。
     */
    public String matchTitleOrDefault() {
        return matchTitle == null || matchTitle.isBlank() ? title : matchTitle;
    }

    public String matchDescriptionOrDefault() {
        return matchDescription == null || matchDescription.isBlank() ? description : matchDescription;
    }

    public String matchLocationOrDefault() {
        return matchLocation == null || matchLocation.isBlank() ? location : matchLocation;
    }

    public String rawTitleOrDefault() {
        return rawTitle == null ? title : rawTitle;
    }

    public String rawDescriptionOrDefault() {
        return rawDescription == null ? description : rawDescription;
    }

    public String rawLocationOrDefault() {
        return rawLocation == null ? location : rawLocation;
    }

    public LocalDateTime getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(LocalDateTime deletedAt) {
        this.deletedAt = deletedAt;
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
