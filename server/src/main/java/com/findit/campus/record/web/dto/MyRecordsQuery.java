package com.findit.campus.record.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

/**
 * {@code GET /api/my/records} 的查询条件（契约 §4.12）。
 *
 * <p>与 {@link RecordQuery} 的差别只有一处但很关键：{@code status} 默认 {@code ALL}，
 * 因为三态记录都属于本人，管理视图必须都能查到；公共列表默认只看 {@code PENDING}。
 *
 * <p>没有 {@code keyword}：契约 §4.12 没有这个参数，别为了"顺手"加一个未定义的自由文本检索面。
 */
public class MyRecordsQuery {

    public static final int DEFAULT_PAGE = 1;
    public static final int DEFAULT_PAGE_SIZE = 10;
    public static final int MAX_PAGE_SIZE = 50;

    @Pattern(regexp = "LOST|FOUND", message = "type 取值非法：LOST / FOUND")
    private String type;

    @Pattern(regexp = "PENDING|COMPLETED|CLOSED|ALL",
            message = "status 取值非法：PENDING / COMPLETED / CLOSED / ALL")
    private String status = "ALL";

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

    public int pageOrDefault() {
        return page == null ? DEFAULT_PAGE : page;
    }

    public int pageSizeOrDefault() {
        return pageSize == null ? DEFAULT_PAGE_SIZE : pageSize;
    }

    public String statusOrDefault() {
        return status == null || status.isBlank() ? "ALL" : status;
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
