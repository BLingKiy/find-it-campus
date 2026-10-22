package com.findit.campus.common.error;

import java.util.Map;

import com.findit.campus.common.api.ErrorCode;

/**
 * 业务异常：携带 {@link ErrorCode}，由 {@link GlobalExceptionHandler} 转成统一响应体。
 *
 * <p>service 层发现"业务上不允许"时抛它（记录不存在、状态流转非法、分类停用…），
 * <b>不</b> {@code try/catch} 之后自己决定错误码 —— 那样会把错误码散落到各层，最终写歪。
 *
 * <p>HTTP 状态码不在这里存：只存 {@code ErrorCode}，双轨映射保持在 {@code Enum} 一处。
 *
 * <p>可选的 {@code data} 用于契约要求"错误也要带结构化信息"的少数分支（3.1 引入，契约 §6.1/§8.3）：
 * 状态流转非法要回 {@code {"current":…,"allowed":[…]}}、匹配引擎不可用要回 {@code {"engine":…}}。
 * 没有它的分支一律 {@code null}，与 3.1 之前的行为一致 —— 不要为了"统一"给每个错误都塞 data。
 */
public class BizException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ErrorCode errorCode;
    private final Map<String, Object> data;

    public BizException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
        this.data = null;
    }

    public BizException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.data = null;
    }

    public BizException(ErrorCode errorCode, String message, Map<String, Object> data) {
        super(message);
        this.errorCode = errorCode;
        this.data = data;
    }

    /** 文案用 {@link ErrorCode#message()} 的默认文案。 */
    public static BizException of(ErrorCode errorCode) {
        return new BizException(errorCode, errorCode.message());
    }

    /** 需补充业务细节（如具体 id、当前状态）时用这个。 */
    public static BizException of(ErrorCode errorCode, String message) {
        return new BizException(errorCode, message);
    }

    /** 契约要求错误响应带结构化 data 时用这个（见类注释）。 */
    public static BizException of(ErrorCode errorCode, String message, Map<String, Object> data) {
        return new BizException(errorCode, message, data);
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    /** 错误附加数据；绝大多数分支为 {@code null}。 */
    public Map<String, Object> data() {
        return data;
    }
}
