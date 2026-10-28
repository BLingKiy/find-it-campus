package com.findit.campus.notification.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.findit.campus.auth.session.SessionAuth;
import com.findit.campus.common.api.ApiResponse;
import com.findit.campus.common.api.ErrorCode;
import com.findit.campus.common.error.BizException;
import com.findit.campus.notification.service.NotificationService;
import com.findit.campus.notification.web.dto.NotificationPageResponse;
import com.findit.campus.notification.web.dto.NotificationQuery;
import com.findit.campus.notification.web.dto.NotificationReadResponse;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

/**
 * {@code GET /api/notifications} —— 通知列表 + 未读数（契约 §4.16）。
 *
 * <p>登录门禁：{@code /api/notifications} 已在 {@code AuthPaths.LOGIN_REQUIRED_READS} 登记，
 * 匿名在 Filter 就拿到 401/10006；这里 {@code requireUser} 是第二层。
 *
 * <p>{@code PATCH /{id}/read} 的 id 用 String 接、手工解析：非数字 id 按「通知不存在」返回
 * 404/50004，而不是被全局处理器翻译成 400/10001（契约 §4.17）。
 */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping
    public ApiResponse<NotificationPageResponse> list(@Valid @ModelAttribute NotificationQuery query,
                                                      HttpServletRequest request) {
        long userId = SessionAuth.requireUser(request).id();
        return ApiResponse.success(notificationService.list(query, userId));
    }

    @PatchMapping("/{id}/read")
    public ApiResponse<NotificationReadResponse> markRead(@PathVariable("id") String id,
                                                          HttpServletRequest request) {
        long notificationId = parseNotificationId(id);
        long userId = SessionAuth.requireUser(request).id();
        return ApiResponse.success(notificationService.markRead(notificationId, userId));
    }

    private static long parseNotificationId(String raw) {
        if (raw == null || raw.isBlank()) {
            throw BizException.of(ErrorCode.NOTIFICATION_NOT_FOUND);
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException notANumber) {
            throw BizException.of(ErrorCode.NOTIFICATION_NOT_FOUND);
        }
    }
}
