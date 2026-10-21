package com.findit.campus.common.error;

import com.findit.campus.common.api.ErrorCode;

/**
 * 业务异常：携带 {@link ErrorCode}，由 {@link GlobalExceptionHandler} 转成统一响应体。
 *
 * <p>service 层发现"业务上不允许"时抛它（记录不存在、状态流转非法、分类停用…），
 * <b>不</b> {@code try/catch} 之后自己决定错误码 —— 那样会把错误码散落到各层，最终写歪。
 *
 * <p>HTTP 状态码不在这里存：只存 {@code ErrorCode}，双轨映射保持在 {@code Enum} 一处。
 */
public class BizException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ErrorCode errorCode;

    public BizException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public BizException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    /** 文案用 {@link ErrorCode#message()} 的默认文案。 */
    public static BizException of(ErrorCode errorCode) {
        return new BizException(errorCode, errorCode.message());
    }

    /** 需补充业务细节（如具体 id、当前状态）时用这个。 */
    public static BizException of(ErrorCode errorCode, String message) {
        return new BizException(errorCode, message);
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
