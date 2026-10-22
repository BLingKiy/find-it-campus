package com.findit.campus.common.api;

import org.springframework.http.HttpStatus;

/**
 * 错误码表（基线 {@code docs/architecture-baseline.md} §6.2），新增必须在这里登记。
 *
 * <p>分段：{@code 1xxxx} 通用 · {@code 2xxxx} 记录 · {@code 3xxxx} 匹配 · {@code 4xxxx} 分类。
 * 这里是 code ↔ 默认文案 ↔ HTTP 状态码的<b>唯一</b>映射点：
 * {@link ApiResponse#fail} 从这里取 code 与文案，全局异常处理从这里取 httpStatus。
 *
 * <p>基线 §6.2 表中的 30004（429 匹配过频）当前<b>不实现</b>（§6.2 注明"当前基线不实现"），
 * 故本枚举不登记；若将来启用限流，在此落 {@code MATCH_RATE_LIMITED(30004, TOO_MANY_REQUESTS, "匹配调用过于频繁")}。
 */
public enum ErrorCode {

    OK(0, HttpStatus.OK, "ok"),

    // ---- 1xxxx 通用 ----
    VALIDATION_FAILED(10001, HttpStatus.BAD_REQUEST, "请求参数校验失败"),
    PAYLOAD_TOO_LARGE(10002, HttpStatus.PAYLOAD_TOO_LARGE, "请求体过大"),
    RESOURCE_NOT_FOUND(10003, HttpStatus.NOT_FOUND, "资源不存在"),
    METHOD_NOT_ALLOWED(10004, HttpStatus.METHOD_NOT_ALLOWED, "方法不支持"),
    INTERNAL_ERROR(10005, HttpStatus.INTERNAL_SERVER_ERROR, "服务器内部错误"),

    // ---- 2xxxx 记录 ----
    RECORD_NOT_FOUND(20001, HttpStatus.NOT_FOUND, "记录不存在或已删除"),
    RECORD_ILLEGAL_STATUS_TRANSITION(20002, HttpStatus.CONFLICT, "状态流转非法"),
    RECORD_OPERATION_NOT_ALLOWED(20003, HttpStatus.CONFLICT, "记录当前状态不可执行该操作"),

    // ---- 3xxxx 匹配 ----
    MATCH_INPUT_INVALID(30001, HttpStatus.BAD_REQUEST, "匹配输入无效"),
    MATCH_ENGINE_UNAVAILABLE(30002, HttpStatus.SERVICE_UNAVAILABLE, "匹配引擎不可用"),
    MATCH_ENGINE_TIMEOUT(30003, HttpStatus.GATEWAY_TIMEOUT, "匹配引擎超时"),
    // 30004（429）见类注释：基线登记但当前不实现。

    // ---- 4xxxx 分类 ----
    CATEGORY_NOT_FOUND(40001, HttpStatus.NOT_FOUND, "分类不存在"),
    CATEGORY_INACTIVE(40002, HttpStatus.CONFLICT, "分类停用不可选"),
    ;

    private final int code;
    private final HttpStatus httpStatus;
    private final String message;

    ErrorCode(int code, HttpStatus httpStatus, String message) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.message = message;
    }

    public int code() {
        return code;
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }

    public String message() {
        return message;
    }
}
