package com.findit.campus.common.api;

/**
 * 统一响应体（ADR-005：HTTP 状态码 + body code 双轨）。
 *
 * <p>所有接口（含错误出口）都返回它，字段固定为 {@code {code,message,data,requestId}}。
 *
 * <p><b>唯一构造入口是 {@link #success} / {@link #fail}</b>：controller 不允许
 * {@code new ApiResponse(...)}，也不允许手填 code —— code 只能来自 {@link ErrorCode}，
 * HTTP 状态码只能来自 {@link ErrorCode#httpStatus}。这是 §6.1 里"两处表达同一事实能否不写歪"的
 * 唯一保障手段，代价是 controller 拿不到"随便写一个 body"的自由度（这部分自由度本来就不该有）。
 *
 * <p>取值方法必须是 JavaBean 风格的 {@code getXxx()}：Jackson 默认按 bean 属性序列化，
 * 写成 record 风格的 {@code code()} 会导致响应体丢失字段（这里踩过一次，body 只剩 {@code {"success":true}}）。</p>
 *
 * @param <T> data 的类型
 */
public final class ApiResponse<T> {

    private final int code;
    private final String message;
    private final T data;
    private final String requestId;

    private ApiResponse(int code, String message, T data, String requestId) {
        this.code = code;
        this.message = message;
        this.data = data;
        this.requestId = requestId;
    }

    /** 成功出口（code=0，HTTP 200），{@code requestId} 从当前请求上下文取。 */
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(ErrorCode.OK.code(), ErrorCode.OK.message(), data, RequestContext.requestId());
    }

    /** 成功出口并覆盖文案：仅在 {@code message} 需要携带业务信息（如"已提交")时使用。 */
    public static <T> ApiResponse<T> success(T data, String message) {
        return new ApiResponse<>(ErrorCode.OK.code(), message, data, RequestContext.requestId());
    }

    /**
     * 失败出口：code 与 httpStatus 唯一来源 {@link ErrorCode}。
     *
     * @param errorCode 错误码，决定 body 的 code 与 HTTP 状态码
     * @param data      错误附加数据；无附加数据时传 {@code null}
     */
    public static <T> ApiResponse<T> fail(ErrorCode errorCode, T data) {
        return new ApiResponse<>(errorCode.code(), errorCode.message(), data, RequestContext.requestId());
    }

    /**
     * 失败出口（覆盖对外文案）：仅当契约要求"同一 code、不同 message"时使用
     * （目前只有 10006 的登录失败分支，见 {@code BizException#withPublicMessage}）。
     *
     * @param message 对外文案；必须是不含内部细节的固定措辞
     */
    public static <T> ApiResponse<T> fail(ErrorCode errorCode, String message, T data) {
        return new ApiResponse<>(errorCode.code(), message, data, RequestContext.requestId());
    }

    /** 失败出口（无附加数据）。 */
    public static <T> ApiResponse<T> fail(ErrorCode errorCode) {
        return fail(errorCode, null);
    }

    /** JavaBean getter 命名不可改：Jackson 按它决定响应体字段名。 */
    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public T getData() {
        return data;
    }

    public String getRequestId() {
        return requestId;
    }
}
