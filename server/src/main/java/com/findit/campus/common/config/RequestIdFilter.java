package com.findit.campus.common.config;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import com.findit.campus.common.api.RequestContext;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 给每次请求生成 {@code requestId}：写入 MDC（日志用）与响应头 {@code X-Request-Id}（前端报错时可直接抄给用户）。
 *
 * <p>为什么必须有它：响应体的 {@code requestId} 要能在日志里被 grep 到，两者必须同源 —— 同源靠"进入过滤器就落 MDC"保证，
 * 而不是靠每个 controller 记得传。
 *
 * <p>顺带记一行访问日志（方法、路径、状态、耗时），满足 §6.3「一次请求一行 INFO」；异步线程拿不到这个 id 属已知代价，
 * 3.x 若引入异步需自行透传。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestIdFilter.class);

    /** 上游网关可能带 X-Request-Id：有则沿用，便于跨系统串起来；无则本地生成。 */
    public static final String HEADER = "X-Request-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String incoming = request.getHeader(HEADER);
        MDC.put(RequestContext.MDC_KEY, StringUtils.hasText(incoming) ? incoming : RequestContext.requestId());
        response.setHeader(HEADER, MDC.get(RequestContext.MDC_KEY));
        long start = System.currentTimeMillis();
        try {
            chain.doFilter(request, response);
        } finally {
            log.info("{} {} -> {} ({} ms)", request.getMethod(), request.getRequestURI(), response.getStatus(),
                    System.currentTimeMillis() - start);
            MDC.remove(RequestContext.MDC_KEY);
        }
    }
}
