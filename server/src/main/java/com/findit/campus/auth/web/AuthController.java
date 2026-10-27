package com.findit.campus.auth.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.findit.campus.auth.service.AuthService;
import com.findit.campus.auth.session.SessionAuth;
import com.findit.campus.auth.web.dto.LoginRequest;
import com.findit.campus.auth.web.dto.UserResponse;
import com.findit.campus.common.api.ApiResponse;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

/**
 * {@code /api/auth} —— 登录 / 登出 / 当前用户（契约 §4.9–§4.11）。
 *
 * <p>controller 只做"绑参数 → 调 service → 包响应"，错误码全部由
 * {@code GlobalExceptionHandler} 出（含登录失败的 401/10006）。这里唯一的例外是
 * <b>登出要清 Cookie</b>，必须拿到响应头，所以返回 {@code ResponseEntity}；
 * 状态码仍是 200，失败路径（无业务错误）不经过这里。
 *
 * <p><b>没有注册接口</b>，也没有"找回口令"：账号只由 seed / 运维路径产生。
 * 这是范围决定，不是还没做 —— 加注册要先回答"谁能注册、怎么校验是本校学生"。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * 登录：成功 200 + {@code Set-Cookie: JSESSIONID=…; Path=/; HttpOnly; SameSite=Lax}。
     *
     * <p>Cookie 由容器在会话建立/换 id 时下发，本方法不手写 —— 手写会与
     * {@code server.servlet.session.cookie.*} 形成两套口径，且拿不到容器生成的 id。
     */
    @PostMapping("/login")
    public ApiResponse<UserResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        return ApiResponse.success(authService.login(request, httpRequest), "登录成功");
    }

    /**
     * 登出：幂等；未登录调用也 200。
     *
     * <p>显式下发一个 {@code Max-Age=0} 的同名 Cookie：容器在 {@code invalidate()} 时不负责删浏览器那头。
     */
    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(HttpServletRequest httpRequest) {
        authService.logout(httpRequest);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, SessionAuth.clearedSessionCookie().toString())
                .body(ApiResponse.success(null, "已退出"));
    }

    /** 当前用户：未登录 → 401 + 10006（拦截器先拦，这里再兜一次 requireUser）。 */
    @GetMapping("/me")
    public ApiResponse<UserResponse> me(HttpServletRequest httpRequest) {
        return ApiResponse.success(authService.currentUser(httpRequest));
    }
}
