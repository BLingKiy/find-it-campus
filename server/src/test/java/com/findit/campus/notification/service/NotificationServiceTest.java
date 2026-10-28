package com.findit.campus.notification.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.findit.campus.common.api.ErrorCode;
import com.findit.campus.common.error.BizException;
import com.findit.campus.notification.domain.Notification;
import com.findit.campus.notification.mapper.NotificationMapper;
import com.findit.campus.notification.web.dto.NotificationReadResponse;

/**
 * {@code PATCH /api/notifications/{id}/read}：已读幂等（readAt 保持第一次）、unreadCount 只减一次、越权 403。
 */
@DisplayName("NotificationService.markRead：幂等 / 越权 / 404")
class NotificationServiceTest {

    private NotificationMapper notificationMapper;
    private NotificationService service;

    @BeforeEach
    void setUp() {
        notificationMapper = mock(NotificationMapper.class);
        service = new NotificationService(notificationMapper);
    }

    @Test
    @DisplayName("首次标记：写 read_at，返回更新后的总未读数")
    void firstReadWritesTimestamp() {
        Notification notification = notification(1L, 7L, null);
        when(notificationMapper.selectById(1L)).thenReturn(notification);
        when(notificationMapper.markRead(eq(1L), eq(7L), any())).thenReturn(1);
        when(notificationMapper.countUnreadByUser(7L)).thenReturn(0L);

        NotificationReadResponse response = service.markRead(1L, 7L);

        assertEquals(1L, response.getId());
        assertEquals(true, response.getRead());
        assertEquals(0L, response.getUnreadCount());
        assertEquals(notification.getReadAt(), response.getReadAt());
    }

    @Test
    @DisplayName("已读再读：不写库，readAt 保持第一次")
    void secondReadKeepsFirstTimestamp() {
        LocalDateTime first = LocalDateTime.of(2026, 10, 27, 9, 0);
        when(notificationMapper.selectById(1L)).thenReturn(notification(1L, 7L, first));
        when(notificationMapper.countUnreadByUser(7L)).thenReturn(0L);

        NotificationReadResponse response = service.markRead(1L, 7L);

        verify(notificationMapper, never()).markRead(anyLong(), anyLong(), any());
        assertEquals(first, response.getReadAt());
    }

    @Test
    @DisplayName("并发已读：update 影响 0 行时以库里的第一次时间为准")
    void concurrentReadFallsBackToStoredTimestamp() {
        LocalDateTime first = LocalDateTime.of(2026, 10, 27, 9, 0);
        when(notificationMapper.selectById(1L))
                .thenReturn(notification(1L, 7L, null))
                .thenReturn(notification(1L, 7L, first));
        when(notificationMapper.markRead(anyLong(), anyLong(), any())).thenReturn(0);
        when(notificationMapper.countUnreadByUser(7L)).thenReturn(0L);

        assertEquals(first, service.markRead(1L, 7L).getReadAt());
    }

    @Test
    @DisplayName("他人通知：403/10007，零写入")
    void otherUsersNotificationIsForbidden() {
        when(notificationMapper.selectById(1L)).thenReturn(notification(1L, 8L, null));

        assertEquals(ErrorCode.FORBIDDEN,
                assertThrows(BizException.class, () -> service.markRead(1L, 7L)).errorCode());
        verify(notificationMapper, never()).markRead(anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("不存在：404/50004")
    void missingNotificationIsNotFound() {
        when(notificationMapper.selectById(1L)).thenReturn(null);

        assertEquals(ErrorCode.NOTIFICATION_NOT_FOUND,
                assertThrows(BizException.class, () -> service.markRead(1L, 7L)).errorCode());
    }

    private static Notification notification(Long id, Long userId, LocalDateTime readAt) {
        Notification notification = new Notification();
        notification.setId(id);
        notification.setUserId(userId);
        notification.setPairId(101L);
        notification.setType("PAIR_UNLOCK");
        notification.setTitle("对方已公开联系方式");
        notification.setBody("点击查看");
        notification.setReadAt(readAt);
        notification.setCreatedAt(LocalDateTime.of(2026, 10, 27, 8, 0));
        return notification;
    }
}
