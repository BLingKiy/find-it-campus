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
    private String title;
    private String description;
    private Long categoryId;
    private String categoryName;
    private String location;
    private LocalDateTime occurredAt;
    private String contact;
    private String publisherName;
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
