package com.findit.campus.notification.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.findit.campus.common.api.ErrorCode;
import com.findit.campus.common.error.BizException;
import com.findit.campus.notification.domain.Notification;
import com.findit.campus.notification.mapper.NotificationMapper;
import com.findit.campus.notification.web.dto.NotificationItemResponse;
import com.findit.campus.notification.web.dto.NotificationPageResponse;
import com.findit.campus.notification.web.dto.NotificationQuery;
import com.findit.campus.notification.web.dto.NotificationReadResponse;

/**
 * 通知列表（契约 §4.16）：只返回 {@code user_id = session.userId}，输出再遮蔽一次。
 *
 * <p>能力边界：读与输出遮蔽（C1）+ 已读（E1）；通知的产生属于 D1，仍不在本类。写入语义
 * （唯一键去重、阈值、失效 pair）与读取/已读语义是两条独立的规则集，不从本类反向调用创建。
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationMapper notificationMapper;

    public NotificationService(NotificationMapper notificationMapper) {
        this.notificationMapper = notificationMapper;
    }

    public NotificationPageResponse list(NotificationQuery query, long userId) {
        int page = query.pageOrDefault();
        int pageSize = query.pageSizeOrDefault();
        long total = notificationMapper.countByUser(userId);
        List<NotificationItemResponse> items = total == 0
                ? List.of()
                : notificationMapper.selectByUser(userId, (page - 1) * pageSize, pageSize).stream()
                        .map(NotificationItemResponse::of)
                        .toList();
        long unread = notificationMapper.countUnreadByUser(userId);
        log.debug("通知列表 userId={} total={} unread={}", userId, total, unread);
        return new NotificationPageResponse(unread, page, pageSize, total, items);
    }

    /**
     * 标记已读（契约 §4.17）。
     *
     * <p>判定顺序：不存在 404/50004 → 非接收人 403/10007 → 已读幂等。
     * 「他人通知」返回 403 而不是 404，是契约明确的口径：接收人之外的人不需要知道这条通知是否存在，
     * 但契约选择了 403，前端据此区分「换个人来操作」与「已经没了」。
     *
     * <p>已读再读：不写库，{@code readAt} 保持第一次的时间；{@code unreadCount} 是更新后的总未读数，
     * 不会重复递减。
     */
    public NotificationReadResponse markRead(long notificationId, long userId) {
        Notification notification = notificationMapper.selectById(notificationId);
        if (notification == null) {
            throw BizException.of(ErrorCode.NOTIFICATION_NOT_FOUND);
        }
        if (!Objects.equals(notification.getUserId(), userId)) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
        if (notification.getReadAt() == null) {
            LocalDateTime now = LocalDateTime.now();
            int updated = notificationMapper.markRead(notificationId, userId, now);
            if (updated > 0) {
                notification.setReadAt(now);
            } else {
                // 并发下已被另一个请求标记：以库里的第一次时间为准，不覆盖
                notification = notificationMapper.selectById(notificationId);
                if (notification == null) {
                    throw BizException.of(ErrorCode.NOTIFICATION_NOT_FOUND);
                }
            }
        }
        NotificationReadResponse response = new NotificationReadResponse();
        response.setId(notification.getId());
        response.setRead(true);
        response.setReadAt(notification.getReadAt());
        response.setUnreadCount(notificationMapper.countUnreadByUser(userId));
        return response;
    }
}
