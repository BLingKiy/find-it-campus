package com.findit.campus.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 层配置：CORS 兜底（ADR-012）。
 *
 * <p>主路径是前端 Vite 代理（浏览器视角同源，零 CORS）；这条配置服务于"直接调 18080 / 非代理消费者"。
 * 白名单写死两个开发源而<b>不用 {@code *}</b>：浏览器禁止 {@code *} 与凭据同时使用。
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

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("http://localhost:5173", "http://127.0.0.1:5173")
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("X-Request-Id")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
