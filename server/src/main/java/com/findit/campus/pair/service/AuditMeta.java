package com.findit.campus.pair.service;

import com.findit.campus.common.api.RequestContext;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 写审计时随请求带来的非业务字段：requestId / 客户端 IP / User-Agent。
 *
 * <p>为什么在 web 边界一次取出、而不是让 service 直接吃 {@code HttpServletRequest}：
 * service 单测不需要 mock Servlet；同时 IP 取值规则（XFF 首段、列宽截断）只有一处。
 *
 * <p>X-Forwarded-For 在无反代时可由客户端伪造。审计字段用于排障与事后追溯，
 * 不作为鉴权依据；生产部署应在可信反代上覆写该头（部署说明已注明）。
 */
public record AuditMeta(String requestId, String ip, String userAgent) {

    private static final int IP_MAX = 45;
    private static final int UA_MAX = 255;
    private static final int REQUEST_ID_MAX = 64;

    public static AuditMeta from(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        String ip = request.getRemoteAddr();
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            ip = (comma >= 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        return new AuditMeta(
                truncate(RequestContext.requestId(), REQUEST_ID_MAX),
                truncate(ip, IP_MAX),
                truncate(request.getHeader("User-Agent"), UA_MAX));
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }
}
