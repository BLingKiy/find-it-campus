package com.findit.campus.auth.web;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.findit.campus.auth.domain.SessionUser;
import com.findit.campus.auth.service.AuthService;
import com.findit.campus.auth.session.SessionAuth;
import com.findit.campus.auth.web.dto.UserResponse;
import com.findit.campus.common.api.ErrorCode;
import com.findit.campus.common.error.BizException;

/**
 * {@code /api/auth/*} 的 HTTP 形状与 401 统一出口（契约 §4.9–§4.11）。
 *
 * <p>AuthService 全部 mock：本类验的是"错误怎么变成响应"——401 的 code/message/data/requestId 形状、
 * 登出清 Cookie 的响应头、参数校验的 fieldErrors，这些都与数据库无关。
 * 真正的 bcrypt 校验与会话写入在 {@code AuthServiceTest} 与真机证据里。
 */
@WebMvcTest(controllers = AuthController.class)
@DisplayName("/api/auth 登录、登出、当前用户")
class AuthApiTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthService authService;

    @Test
    @DisplayName("登录成功：200 + data{id,studentNo,displayName} + message=登录成功")
    void loginSuccessShape() throws Exception {
        when(authService.login(any(), any())).thenReturn(userResponse());

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"studentNo":"demo01","password":"Demo@123456"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("登录成功"))
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.studentNo").value("demo01"))
                .andExpect(jsonPath("$.data.displayName").value("演示甲"))
                // 出参永远不含口令哈希（DTO 只有三个字段）
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist());
    }

    @Test
    @DisplayName("登录失败：401 + 10006 + 学号或密码不正确（与账号是否存在无关）")
    void loginFailureIsUniform() throws Exception {
        when(authService.login(any(), any()))
                .thenThrow(BizException.withPublicMessage(ErrorCode.UNAUTHORIZED, "学号或密码不正确"));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"studentNo":"demo01","password":"wrong"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(10006))
                .andExpect(jsonPath("$.message").value("学号或密码不正确"))
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    @DisplayName("登录参数：缺学号 / 缺口令 → 400 + 10001 + fieldErrors（不是 401）")
    void loginValidationErrors() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"password":"x"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(10001))
                .andExpect(jsonPath("$.data.fieldErrors[*].field", containsInAnyOrder("studentNo")));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"studentNo":"demo01","password":""}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(10001))
                .andExpect(jsonPath("$.data.fieldErrors[*].field", containsInAnyOrder("password")));
    }

    @Test
    @DisplayName("GET /api/auth/me 无会话 → 401 + 10006 + 请先登录（不是 500、不是 200+null）")
    void meWithoutSessionIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(10006))
                .andExpect(jsonPath("$.message").value("请先登录"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
        verifyNoInteractions(authService);
    }

    @Test
    @DisplayName("GET /api/auth/me 已登录 → 200 + 本人身份")
    void meWithSession() throws Exception {
        when(authService.currentUser(any())).thenReturn(userResponse());

        mockMvc.perform(get("/api/auth/me").session(signedInSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.studentNo").value("demo01"));
    }

    @Test
    @DisplayName("登出：未登录也 200（幂等），且下发 Max-Age=0 的清 Cookie 指令")
    void logoutIsIdempotentAndClearsCookie() throws Exception {
        mockMvc.perform(post("/api/auth/logout"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("已退出"))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, allOf(
                        containsString("JSESSIONID="),
                        containsString("Path=/"),
                        containsString("Max-Age=0"),
                        containsString("HttpOnly"),
                        containsString("SameSite=Lax"))));
    }

    private static MockHttpSession signedInSession() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionAuth.SESSION_ATTRIBUTE, new SessionUser(1L, "demo01", "演示甲"));
        return session;
    }

    private static UserResponse userResponse() {
        // UserResponse 没有 setter（只由 domain 构造），这里借 from(SessionUser) 造一份等价数据
        return UserResponse.from(new SessionUser(1L, "demo01", "演示甲"));
    }
}
