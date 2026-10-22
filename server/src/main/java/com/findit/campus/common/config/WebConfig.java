package com.findit.campus.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 层配置。当前只有 CORS —— 跨域的<b>兜底</b>路径（ADR-012）。
 *
 * <p>主路径是前端 Vite 代理（浏览器视角同源，零 CORS）；这条配置服务于"直接调 18080 / 非代理消费者"。
 * 白名单写死两个开发源而<b>不用 {@code *}</b>：本项目无鉴权体系，但一旦将来加了 cookie 凭据，
 * {@code *} + credentials 是被浏览器拒绝的组合，提前堵掉这条路。
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
                .allowCredentials(false)
                .maxAge(3600);
    }
}
