package com.findit.campus.category.web.dto;

import com.findit.campus.category.domain.Category;

/**
 * 分类字典出参（契约 §7）。
 *
 * <p>字段名与契约一一对应（{@code id/code/name/sortOrder/active}），改名必须同步改契约 —— 这是刻意的：
 * 出参 DTO 与契约同名，前端 TS 类型与后端才对得上，漏改会在联调第一天暴露。
 *
 * <p>不含 {@code keywords}：它是本地匹配实现的内部加权词表，不属于对外契约。
 */
public class CategoryResponse {

    private Long id;
    private String code;
    private String name;
    private Integer sortOrder;
    private Boolean active;

    public static CategoryResponse from(Category category) {
        CategoryResponse response = new CategoryResponse();
        response.id = category.getId();
        response.code = category.getCode();
        response.name = category.getName();
        response.sortOrder = category.getSortOrder();
        response.active = Boolean.TRUE.equals(category.getActive());
        return response;
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public Boolean getActive() {
        return active;
    }
}
