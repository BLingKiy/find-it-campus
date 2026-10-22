package com.findit.campus.record.domain;

import java.time.LocalDateTime;

/**
 * 记录的卡片读模型（列表项 + 匹配的回表结果共用）。
 *
 * <p>为什么不是直接用 {@link Record}：列表一次最多 50 行，契约 §3.2 又明确"列表刻意不返回
 * {@code description}/{@code contact}/{@code publisherName}"（省流量 + 最小暴露）。
 * 若沿用实体，就要把整行（含 TEXT 的 description）SELECT 出来再在 service 里丢掉 ——
 * 白付一次 IO，还留下"不小心把 contact 透出去"的隐患。读模型让"查多少列"这件事在 SQL 里就说清楚。
 *
 * <p>它同时是 {@code /api/match} 回表补展示字段的载体（契约 §8.2：展示字段与详情接口同值），
 * 于是"列表卡片"与"匹配结果卡片"不会各自长出一份字段集合。
 */
public class RecordSummary {

    private Long id;
    private RecordType type;
    private RecordStatus status;
    private String title;
    private Long categoryId;
    private String categoryName;
    private String location;
    private LocalDateTime occurredAt;
    private LocalDateTime createdAt;

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

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
