package com.findit.campus.notification.service;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.findit.campus.notification.mapper.NotificationMapper;
import com.findit.campus.notification.web.dto.NotificationItemResponse;
import com.findit.campus.notification.web.dto.NotificationPageResponse;
import com.findit.campus.notification.web.dto.NotificationQuery;

/**
 * 通知列表（契约 §4.16）：只返回 {@code user_id = session.userId}，输出再遮蔽一次。
 *
 * <p>能力边界：C1 只做<b>读</b>与输出遮蔽；通知的产生属于 D1。因此这里没有"创建通知"的公开方法，
 * 将来 D1 也不应该为了少建一个类而从这里反向调用 —— 写入语义（唯一键去重、阈值、失效 pair）
 * 与读取语义是两条独立的规则集。
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
}
