package com.findit.campus.notification.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * {@code GET /api/notifications} 的分页参数（契约 §4.16：page 默认 1、pageSize 默认 10、上限 50）。
 */
public class NotificationQuery {

    public static final int DEFAULT_PAGE = 1;
    public static final int DEFAULT_PAGE_SIZE = 10;
    public static final int MAX_PAGE_SIZE = 50;

    @Min(value = 1, message = "page 需大于等于 1")
    private Integer page = DEFAULT_PAGE;

    @Min(value = 1, message = "pageSize 需大于等于 1")
    @Max(value = MAX_PAGE_SIZE, message = "pageSize 不得超过 50")
    private Integer pageSize = DEFAULT_PAGE_SIZE;

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

    public int pageOrDefault() {
        return page == null ? DEFAULT_PAGE : page;
    }

    public int pageSizeOrDefault() {
        return pageSize == null ? DEFAULT_PAGE_SIZE : pageSize;
    }
}
