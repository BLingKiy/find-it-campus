package com.findit.campus.record.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * {@code GET /api/records} 的查询条件（契约 §3.1）。
 *
 * <p>字段默认值就是契约默认值（{@code status=PENDING}、{@code page=1}、{@code pageSize=10}、
 * {@code sortBy=createdAt}、{@code sortOrder=desc}），放在这里而不是散在 service 里，
 * 是为了让"默认值"只有一处定义 —— 前端按契约省略参数时拿到的就是它。
 *
 * <p>校验注解全部落在这一层（§6：校验放 DTO）：枚举与范围非法直接产出 {@code 10001 + fieldErrors}，
 * 不需要 service 里再写一遍 if。{@code categoryId} 不加注解是因为"存在性"要查库，
 * 由 service 给 {@code 40001}/{@code 40002}（契约 §3.3）。
 *
 * <p>所有字符串 setter 统一做 {@code strip()}，空串归一成 {@code null}：契约要求"去首尾空格后判长"，
 * 在 setter 里做，Bean Validation 拿到的就是已归一的值（顺序：绑定 → 校验）。
 */
public class RecordQuery {

    /** 分页下限与上限（契约 §1，与前端分页器的取值范围同一口径）。 */
    public static final int DEFAULT_PAGE = 1;
    public static final int DEFAULT_PAGE_SIZE = 10;
    public static final int MAX_PAGE_SIZE = 50;

    @Pattern(regexp = "LOST|FOUND", message = "type 取值非法：LOST / FOUND")
    private String type;

    /** {@code ALL} = 不限状态；缺省 {@code PENDING}（契约 §3.1：默认只看待领）。 */
    @Pattern(regexp = "PENDING|CLAIMED|CLOSED|ALL", message = "status 取值非法：PENDING / CLAIMED / CLOSED / ALL")
    private String status = "PENDING";

    private Long categoryId;

    @Size(max = 64, message = "keyword 长度不得超过 64")
    private String keyword;

    @Min(value = 1, message = "page 需大于等于 1")
    private Integer page = DEFAULT_PAGE;

    @Min(value = 1, message = "pageSize 需大于等于 1")
    @Max(value = MAX_PAGE_SIZE, message = "pageSize 不得超过 50")
    private Integer pageSize = DEFAULT_PAGE_SIZE;

    @Pattern(regexp = "createdAt|occurredAt", message = "sortBy 取值非法：createdAt / occurredAt")
    private String sortBy = "createdAt";

    @Pattern(regexp = "asc|desc", flags = Pattern.Flag.CASE_INSENSITIVE, message = "sortOrder 取值非法：asc / desc")
    private String sortOrder = "desc";

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = normalize(type);
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = normalize(status);
    }

    public Long getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(Long categoryId) {
        this.categoryId = categoryId;
    }

    public String getKeyword() {
        return keyword;
    }

    public void setKeyword(String keyword) {
        this.keyword = normalize(keyword);
    }

    public Integer getPage() {
        return page;
    }

    public void setPage(Integer page) {
        this.page = page;
    }

    public Integer getPageSize() {
        return pageSize;
    }

    public void setPageSize(Integer pageSize) {
        this.pageSize = pageSize;
    }

    public String getSortBy() {
        return sortBy;
    }

    public void setSortBy(String sortBy) {
        this.sortBy = normalize(sortBy);
    }

    public String getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(String sortOrder) {
        this.sortOrder = normalize(sortOrder);
    }

    /** 参数出现但为空（{@code ?page=}）时回落到默认值，而不是把 null 往下传。 */
    public int pageOrDefault() {
        return page == null ? DEFAULT_PAGE : page;
    }

    public int pageSizeOrDefault() {
        return pageSize == null ? DEFAULT_PAGE_SIZE : pageSize;
    }

    /** 缺省/空白一律按契约默认 {@code PENDING}。 */
    public String statusOrDefault() {
        return status == null || status.isBlank() ? "PENDING" : status;
    }

    public String sortByOrDefault() {
        return sortBy == null || sortBy.isBlank() ? "createdAt" : sortBy;
    }

    public String sortOrderOrDefault() {
        return sortOrder == null || sortOrder.isBlank() ? "desc" : sortOrder;
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
