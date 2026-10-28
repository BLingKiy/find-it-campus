package com.findit.campus.notification.web.dto;

import java.util.List;

/**
 * 通知分页响应（契约 §4.16）：在通用分页字段外多一个 {@code unreadCount}（全部未读数，不是本页）。
 *
 * <p>为什么不用 {@code PageResult} 再套一层：契约把 {@code unreadCount} 放在与分页字段<b>同级</b>的
 * {@code data} 下，套一层会变成 {@code data.page.unreadCount}，与契约不符。
 */
public class NotificationPageResponse {

    private final long unreadCount;
    private final int page;
    private final int pageSize;
    private final long total;
    private final List<NotificationItemResponse> list;

    public NotificationPageResponse(long unreadCount, int page, int pageSize, long total,
                                    List<NotificationItemResponse> list) {
        this.unreadCount = unreadCount;
        this.page = page;
        this.pageSize = pageSize;
        this.total = total;
        this.list = list == null ? List.of() : List.copyOf(list);
    }

    public long getUnreadCount() {
        return unreadCount;
    }

    public int getPage() {
        return page;
    }

    public int getPageSize() {
        return pageSize;
    }

    public long getTotal() {
        return total;
    }

    public List<NotificationItemResponse> getList() {
        return list;
    }

    /** 与 {@code PageResult} 同口径：total=0 → 0 页、无下一页。 */
    public int getTotalPages() {
        if (total <= 0 || pageSize <= 0) {
            return 0;
        }
        return (int) Math.ceil((double) total / pageSize);
    }

    public boolean getHasNext() {
        return page < getTotalPages();
    }
}
