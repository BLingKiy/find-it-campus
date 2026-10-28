package com.findit.campus.auth.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.findit.campus.auth.session.SessionAuth;
import com.findit.campus.common.api.ApiResponse;
import com.findit.campus.common.api.ErrorCode;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 登录门禁：在<b>路由之前</b>拦住"需要登录但没会话"的请求，返回 401 + 统一错误体。
 *
 * <p>为什么是 Filter 而不是 HandlerInterceptor：拦截器只对"已被映射到 handler"的请求生效，
 * 一个"该拦但还没实现"的路径（C1 的 {@code /api/my/records}）匿名访问会先得到 404 ——
 * 既让 B2 的验收项无法成立，也把"这个路由存在"泄露给未登录者。
 * Filter 在 DispatcherServlet 之前执行，判的是路径而不是 handler，天然 fail-closed。
 *
 * <p>为什么可以在这里手写 JSON：门禁是唯一一个"在 MVC 之前就必须拒绝"的出口，
 * 它仍然用 {@link ApiResponse#fail(ErrorCode)} 与 {@link ErrorCode#UNAUTHORIZED} 构造 body，
 * 与 {@code GlobalExceptionHandler} 共用同一套 code / 文案 / requestId，
 * 不存在第二份错误码表 —— 这是"第二个渲染点"，不是"第二个失败出口"。
 * 请求号由 {@code RequestIdFilter}（最高优先级）先写入 MDC，因此 body 里的 {@code requestId}
 * 与响应头 {@code X-Request-Id}、日志里的值三者一致。
 *
 * <p>顺序：不加 {@code @Order}（默认最低优先级），确保在 {@code RequestIdFilter} 之后执行；
 * 若将来把门禁提前，必须同时保证 requestId 已经就位，否则 401 body 里会出现"查不到的 id"。
 *
 * <p>不拦的请求：
 * <ul>
 *   <li>白名单写接口（登录、登出、运维扫描）与私有读清单之外的 GET；</li>
 *   <li>OPTIONS 预检（浏览器不带 Cookie）；</li>
 *   <li>非 {@code /api/**} 路径（前端静态资源、错误页）。</li>
 * </ul>
 */
@Component
public class AuthGateFilter extends OncePerRequestFilter {

    private final ObjectMapper objectMapper;

    public AuthGateFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!AuthPaths.requiresLogin(request)) {
            chain.doFilter(request, response);
            return;
        }
        if (SessionAuth.currentUser(request) != null) {
            chain.doFilter(request, response);
            return;
        }
        reject(response);
    }

    /**
     * 401 出口：HTTP 状态 + 统一 body（{@code {code,message,data,requestId}}）。
     *
     * <p>不引导 302 到登录页、不返回空 body：前端拦截器按 {@code code === 0} 判成功，
     * 302/空 body 会让它走进"未知错误"分支（B3 会依赖这个约定）。
     */
    private void reject(HttpServletResponse response) throws IOException {
        response.setStatus(ErrorCode.UNAUTHORIZED.httpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(), ApiResponse.fail(ErrorCode.UNAUTHORIZED));
    }
}
