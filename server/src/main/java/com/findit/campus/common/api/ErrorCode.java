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
    /**
     * 未登录 / 会话失效 / 凭证错误（契约 §1.1、§6：HTTP 401，body code 10006）。
     *
     * <p>默认文案是"请先登录"，用于拦截器与 {@code /api/auth/me}；<b>登录失败</b>必须换成
     * "学号或密码不正确"（防账号枚举），但仍是同一个 code 与 401 —— 那个分支用
     * {@link com.findit.campus.common.error.BizException#withPublicMessage} 显式指定对外文案，
     * 不在这里再登记一个同 code 的枚举值（否则 10006 的含义就分裂成两条）。
     */
    UNAUTHORIZED(10006, HttpStatus.UNAUTHORIZED, "请先登录"),
    /**
     * 越权（契约 §6：HTTP 403，body code 10007）。
     *
     * <p>与 20001（404）刻意分流：403 = "这条记录对你可见，但你不是所有者"；
     * 404 = "按可见性规则你连它存在都不该知道"（CLOSED、到期 COMPLETED、非合格对端）。
     * 两者合并成一个码，前端就无法区分"换个人来操作"和"这条已经不可见"。
     */
    FORBIDDEN(10007, HttpStatus.FORBIDDEN, "无权操作该资源"),

    // ---- 2xxxx 记录 ----
    RECORD_NOT_FOUND(20001, HttpStatus.NOT_FOUND, "记录不存在或已删除"),
    RECORD_ILLEGAL_STATUS_TRANSITION(20002, HttpStatus.CONFLICT, "状态流转非法"),
    RECORD_OPERATION_NOT_ALLOWED(20003, HttpStatus.CONFLICT, "记录当前状态不可执行该操作"),
    /**
     * 配对不存在或任一端记录已删除（契约 §4.13 / §4.14 / §4.15）。
     *
     * <p>与 50005 刻意分流：20004 是「这一行/这一端已经没了」；50005 是「行还在，但所属记录
     * CLOSED 或 COMPLETED 到期，pair 失效」。CLOSED 一律走 50005，不用 20004。
     */
    PAIR_NOT_FOUND(20004, HttpStatus.NOT_FOUND, "配对不存在或任一端记录已删除"),
    /**
     * 标记完成的前置未满足（契约 §4.5：HTTP 409，body code 20005，<b>零写入</b>）。
     *
     * <p>{@code data} 固定带 {@code currentStatus/required/matchedPairCount}：
     * 前端据此把「标记完成」置灰，而不是等用户点了才弹一个"未知原因"的 409。
     */
    PAIR_REQUIRED_FOR_COMPLETE(20005, HttpStatus.CONFLICT, "需双方确认匹配后才能标记完成"),

    // ---- 3xxxx 匹配 ----
    MATCH_INPUT_INVALID(30001, HttpStatus.BAD_REQUEST, "匹配输入无效"),
    MATCH_ENGINE_UNAVAILABLE(30002, HttpStatus.SERVICE_UNAVAILABLE, "匹配引擎不可用"),
    MATCH_ENGINE_TIMEOUT(30003, HttpStatus.GATEWAY_TIMEOUT, "匹配引擎超时"),
    // 30004（429）见类注释：基线登记但当前不实现。

    // ---- 4xxxx 分类 ----
    CATEGORY_NOT_FOUND(40001, HttpStatus.NOT_FOUND, "分类不存在"),
    CATEGORY_INACTIVE(40002, HttpStatus.CONFLICT, "分类停用不可选"),

    // ---- 5xxxx 配对（D1 起）----
    /** 档位不足：{@code score < contact_unlock_threshold}（契约 §4.13/§4.14/§4.15）。 */
    CONTACT_TIER_INSUFFICIENT(50002, HttpStatus.FORBIDDEN, "匹配度不足，无法查看联系方式"),
    /** pair 已处于 MATCHED / IGNORED 终态，再改一律 409（契约 §4.13）。 */
    PAIR_STATE_CONFLICT(50003, HttpStatus.CONFLICT, "配对状态已变更，无法执行该操作"),
    /** 通知不存在（契约 §4.17）。 */
    NOTIFICATION_NOT_FOUND(50004, HttpStatus.NOT_FOUND, "通知不存在"),
    /** 所属记录 CLOSED 或 COMPLETED 到期，pair 失效（契约 §4.13/§4.14/§4.15）。 */
    PAIR_INVALID(50005, HttpStatus.FORBIDDEN, "该配对已失效"),
    /**
     * 兜底扫描单飞：已有一轮在跑（契约 §4.18 的 409）。
     *
     * <p>为什么单独给一个码而不是复用 20002/20003：运维脚本要根据这个码决定"稍后重试"，
     * 复用记录状态码会让调用方误以为某条记录状态非法。契约 §4.18 只写了「409」没写 body code，
     * 这里补一个码并在交付说明里点明，避免前端/脚本按 message 匹配。
     */
    PAIR_SCAN_IN_PROGRESS(50006, HttpStatus.CONFLICT, "兜底扫描正在运行，请稍后重试"),
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
