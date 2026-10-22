package com.findit.campus.category.domain;

import java.time.LocalDateTime;

/**
 * {@code t_category} 实体：与列一一对应。
 *
 * <p>{@code keywords} 是本地匹配实现的内部加权词表（3.2 用），<b>不出现在任何对外响应里</b>
 * （契约 §7：避免前端"顺手用上"造成耦合 —— 它一旦被前端依赖就变成了契约的一部分）。
 */
public class Category {

    private Long id;
    private String code;
    private String name;
    private String keywords;
    private Integer sortOrder;
    private Boolean active;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getKeywords() {
        return keywords;
    }

    public void setKeywords(String keywords) {
        this.keywords = keywords;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }

    /** {@code is_active} 列（TINYINT）转成 boolean 出参（契约 §1：不用 0/1）。 */
    public Boolean getActive() {
        return active;
    }

    public void setActive(Boolean active) {
        this.active = active;
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
