package com.findit.campus.notification.domain;

import java.time.LocalDateTime;

/**
 * {@code t_notification} 实体（通知上下文所有）。
 *
 * <p>正文在<b>写入前</b>就应打码（A1 H6/H8、D1 负责）；C1 的输出仍再走一次
 * {@code MaskingPolicy}，是纵深防御：历史数据、手工修库、未来某个写入分支忘了打码，
 * 都不会让手机号从通知出口漏出去。输出层永远是最后一道闸。
 */
public class Notification {

    private Long id;
    private Long userId;
    private Long pairId;
    private String type;
    private String title;
    private String body;
    private LocalDateTime readAt;
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getPairId() {
        return pairId;
    }

    public void setPairId(Long pairId) {
        this.pairId = pairId;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }

    public LocalDateTime getReadAt() {
        return readAt;
    }

    public void setReadAt(LocalDateTime readAt) {
        this.readAt = readAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
