package com.findit.campus.notification.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.findit.campus.auth.session.SessionAuth;
import com.findit.campus.common.api.ApiResponse;
import com.findit.campus.notification.service.NotificationService;
import com.findit.campus.notification.web.dto.NotificationPageResponse;
import com.findit.campus.notification.web.dto.NotificationQuery;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

/**
 * {@code GET /api/notifications} —— 通知列表 + 未读数（契约 §4.16）。
 *
 * <p>登录门禁：{@code /api/notifications} 已在 {@code AuthPaths.LOGIN_REQUIRED_READS} 登记，
 * 匿名在 Filter 就拿到 401/10006；这里 {@code requireUser} 是第二层。
 *
 * <p>只实现 {@code GET}：{@code PATCH /api/notifications/{id}/read} 属于 E1（通知与 pair 联动）。
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
}
