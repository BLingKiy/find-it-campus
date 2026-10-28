package com.findit.campus.common.config;

import java.io.IOException;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * {@code /api/**} 的响应头基线（P2-2 / P2-5）：禁止缓存个性化内容，并补最小安全头。
 *
 * <p><b>为什么必须 no-store</b>：API 的响应体随 Cookie 里的会话变化 —— 详情页对所有者返回明文
 * {@code contact}，{@code /my}、通知、match 更是只属于当前用户；COMPLETED 详情还靠"合格对端"
 * 的 404 门槛控制可见性。当前单实例本地部署没有共享缓存，但一旦前面加了 CDN/反代缓存，
 * 没有 {@code Cache-Control} 的 200 就可能被缓存并按 URL 回给另一个用户，直接绕过鉴权与 404 门槛。
 * {@code Vary: Cookie} 是第二道保险：即使将来放开某条响应，缓存键也必须带上身份。
 *
 * <p><b>代价</b>：公共列表（{@code GET /api/records}）也一起不可缓存。这是有意的保守取舍 ——
 * 现阶段没有 CDN，收益为零；将来若确实要缓存公共列表，应当新增明确的"匿名公共响应"清单，
 * 而不是从"漏设头"里省出来。按路径前缀一刀切，比在十几个 controller 上逐个记得设置可靠。
 *
 * <p>顺序：在 {@code RequestIdFilter} 之后、{@code AuthGateFilter} 之前。这样 401 的短路响应
 * 也带得上 no-store 与安全头（门禁在 Filter 里直接写响应，不会经过 MVC）。
 *
 * <p>安全头只作用于 {@code /api/**} 的 JSON 响应，不碰前端静态资源，因此不会破坏页面内联脚本。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ApiResponseHeadersFilter extends OncePerRequestFilter {

    static final String CACHE_CONTROL_VALUE = "no-store";
    static final String VARY_VALUE = "Cookie";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !path.startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL_VALUE);
        response.setHeader(HttpHeaders.VARY, VARY_VALUE);
        // P2-5 的最小安全头：API 只返回 JSON，禁止嗅探/内嵌/任意资源加载不会影响正常调用
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'");
        chain.doFilter(request, response);
    }
}
