package com.findit.campus.common.api;

import java.util.UUID;

import org.slf4j.MDC;

/**
 * 一次请求的追踪号载体。
 *
 * <p>为什么单独成立：响应体要回 {@code requestId}、日志 MDC 要同一个值，两处必须一致，
 * 否则"前端报过来的 requestId"在日志里查不到。
 * 由 {@code RequestIdFilter} 在进入请求时生成并写入 MDC，本类只负责读取 —— 不在 API 每一处手工传参。
 *
 * <p>MDC 缺失时（如单元测试、异步线程未继承）退化为一个新随机数并补写 MDC：宁多一个 id，
 * 也不让响应体出现 {@code requestId:null}（没有 id 的错误无法排障）。
 */
public final class RequestContext {

    /** MDC key，与 {@code application.yml} 里 logback pattern 的 {@code %X{requestId}} 对应。 */
    public static final String MDC_KEY = "requestId";

    private RequestContext() {
    }

    public static String requestId() {
        String id = MDC.get(MDC_KEY);
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            MDC.put(MDC_KEY, id);
        }
        return id;
    }
}
