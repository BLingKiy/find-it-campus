/**
 * 对外契约的形状：响应外壳、错误码、字段错误、请求上下文。
 *
 * <p>为什么这一类东西必须集中：前端拦截器只认 {@code code}，日志文件里只有 {@code requestId}，
 * 一旦各层可以自定义 body 形状，"统一响应体"就名存实亡，而且出错时是前端先崩。
 */
package com.findit.campus.common.api;
