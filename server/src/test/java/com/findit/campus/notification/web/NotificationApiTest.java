package com.findit.campus.notification.web;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.findit.campus.auth.domain.SessionUser;
import com.findit.campus.auth.session.SessionAuth;
import com.findit.campus.notification.service.NotificationService;
import com.findit.campus.notification.web.dto.NotificationReadResponse;

/**
 * {@code PATCH /api/notifications/{id}/read}：登录门禁 + 非数字 id 404 + data 形状。
 */
@WebMvcTest(controllers = NotificationController.class)
@DisplayName("NotificationController：401 / 非数字 id 404 / read 响应")
class NotificationApiTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private NotificationService notificationService;

    @Test
    @DisplayName("匿名 → 401/10006")
    void anonymousIsUnauthorized() throws Exception {
        mockMvc.perform(patch("/api/notifications/1/read"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(10006));
    }

    @Test
    @DisplayName("非数字 id → 404/50004")
    void nonNumericIdIsNotFound() throws Exception {
        mockMvc.perform(patch("/api/notifications/abc/read").session(session()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(50004));
    }

    @Test
    @DisplayName("已读成功 → data={id,read,readAt,unreadCount}")
    void readReturnsUpdatedUnreadCount() throws Exception {
        NotificationReadResponse response = new NotificationReadResponse();
        response.setId(1L);
        response.setRead(true);
        response.setReadAt(LocalDateTime.of(2026, 10, 27, 9, 0));
        response.setUnreadCount(2L);
        when(notificationService.markRead(eq(1L), eq(7L))).thenReturn(response);

        mockMvc.perform(patch("/api/notifications/1/read").session(session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.read").value(true))
                .andExpect(jsonPath("$.data.readAt").value("2026-10-27 09:00:00"))
                .andExpect(jsonPath("$.data.unreadCount").value(2));
    }

    private static MockHttpSession session() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionAuth.SESSION_ATTRIBUTE, new SessionUser(7L, "demo02", "演示乙"));
        return session;
    }
}
