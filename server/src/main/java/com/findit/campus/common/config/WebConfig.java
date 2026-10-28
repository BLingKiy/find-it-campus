package com.findit.campus.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 层配置：CORS 兜底（ADR-012）。
 *
 * <p>主路径是前端 Vite 代理（浏览器视角同源，零 CORS）；这条配置服务于"直接调 18080 / 非代理消费者"。
 * 白名单<b>不用 {@code *}</b>：浏览器禁止 {@code *} 与凭据同时使用。
 *
 * <p><b>白名单是"本机 loopback 的任意端口"，不是两个固定开发源</b>。部署包由 nginx 在 8088 提供页面，
 * 开发用 Vite 5173，宿主机还可能改端口；只写 5173 会在部署场景漏判。ZHYYKFX-83 的另一半根因就在这里：
 * nginx 用 {@code $host} 丢掉了端口，后端不再把 8088 认成同源，于是落到这张白名单并被 403。
 * nginx 侧已改为 {@code $http_host} 恢复同源判断；本白名单作为第二道防线，覆盖"页面来自本机任意端口"。
 * 端口通配用 Spring 的 {@code [*]} 语法（6.2.19 实测 {@code localhost} / {@code 127.0.0.1} /
 * {@code [::1]} 三类都能命中），因此不需要再引配置项。
 *
 * <p><b>代价</b>：本机任意端口的页面都能携带 Cookie 调用 API —— 对"单机演示/本地开发"可以接受
 * （本机进程本来就能直接访问 18080）；一旦部署到多主机或对外域名，必须收窄为明确的域名白名单，
 * 不能保留 loopback 通配。
 *
 * <p><b>B2 起 {@code allowCredentials} 必须是 {@code true}</b>：登录态是 {@code JSESSIONID} Cookie，
 * 前端按契约用 {@code fetch(..., {credentials:'include'})}。留 {@code false} 会让"直接连 18080"的
 * 场景登录成功后下一个请求仍 401 —— 这个坑在 B3 联调时表现为"后端有问题"，实际是响应头少了一行。
 * {@code allowedHeaders} 保持通配：它不涉及凭据，收紧只会给前端多加一次预检失败。
 *
 * <p><b>登录门禁不在这里</b>：它是 {@code auth.web.AuthGateFilter}（Servlet Filter），
 * 在 DispatcherServlet 之前判路径，因此"该拦但还没实现"的路径也是 401 而不是 404。
 * 用拦截器只能拦到"已映射到 handler"的请求，会漏这一类。
 *
 * <p>其余（异常处理、消息转换）由 Boot 自动装配 + {@code GlobalExceptionHandler} 提供，不在这里重复配。
 */
@Configuration(proxyBeanMethods = false)
public class WebConfig implements WebMvcConfigurer {

    /**
     * 允许携带凭据的浏览器源：本机 loopback 的任意端口（IPv4 / IPv6 / localhost）。
     *
     * <p>用 {@code allowedOriginPatterns} 的端口通配而不是 {@code allowedOrigins} 枚举：
     * 端口在部署/开发/验收之间会变，枚举永远会漏一个。
     */
    private static final String[] LOOPBACK_ORIGIN_PATTERNS = {
            "http://localhost:[*]",
            "http://127.0.0.1:[*]",
            "http://[::1]:[*]"
    };

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns(LOOPBACK_ORIGIN_PATTERNS)
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("X-Request-Id")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
