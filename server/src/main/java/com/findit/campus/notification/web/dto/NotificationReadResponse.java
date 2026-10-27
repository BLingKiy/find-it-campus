package com.findit.campus.notification.web.dto;

import java.time.LocalDateTime;

import com.findit.campus.common.config.JacksonConfig;

import com.fasterxml.jackson.annotation.JsonFormat;

/** {@code PATCH /api/notifications/{id}/read} 的成功 data（契约 §4.17）。 */
public class NotificationReadResponse {

    private Long id;
    private Boolean read;

    @JsonFormat(pattern = JacksonConfig.DATE_TIME_PATTERN)
    private LocalDateTime readAt;

    private Long unreadCount;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Boolean getRead() {
        return read;
    }

    public void setRead(Boolean read) {
        this.read = read;
    }

    public LocalDateTime getReadAt() {
        return readAt;
    }

    public void setReadAt(LocalDateTime readAt) {
        this.readAt = readAt;
    }

    public Long getUnreadCount() {
        return unreadCount;
    }

    public void setUnreadCount(Long unreadCount) {
        this.unreadCount = unreadCount;
    }
}
