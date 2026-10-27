package com.findit.campus.notification.web.dto;

import java.time.LocalDateTime;

import com.findit.campus.common.config.JacksonConfig;
import com.findit.campus.common.masking.MaskingPolicy;
import com.findit.campus.notification.domain.Notification;

import com.fasterxml.jackson.annotation.JsonFormat;

/**
 * 通知列表项（契约 §4.16）：{@code id,type,pairId,title,body,read,createdAt}。
 *
 * <p>{@code title}/{@code body} 在装配时过 {@link MaskingPolicy}。写入路径（D1）本来就应该
 * 写已打码的正文，这里再打一次不是重复劳动：输出层是最后一道闸，历史行/手工修库/未来写入分支
 * 都可能绕过写入约定，而出口只有一个。
 */
public class NotificationItemResponse {

    private Long id;
    private String type;
    private Long pairId;
    private String title;
    private String body;
    private Boolean read;

    @JsonFormat(pattern = JacksonConfig.DATE_TIME_PATTERN)
    private LocalDateTime createdAt;

    public static NotificationItemResponse of(Notification notification) {
        NotificationItemResponse response = new NotificationItemResponse();
        response.id = notification.getId();
        response.type = notification.getType();
        response.pairId = notification.getPairId();
        response.title = MaskingPolicy.maskDescription(notification.getTitle());
        response.body = MaskingPolicy.maskDescription(notification.getBody());
        response.read = notification.getReadAt() != null;
        response.createdAt = notification.getCreatedAt();
        return response;
    }

    public Long getId() {
        return id;
    }

    public String getType() {
        return type;
    }

    public Long getPairId() {
        return pairId;
    }

    public String getTitle() {
        return title;
    }

    public String getBody() {
        return body;
    }

    public Boolean getRead() {
        return read;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
