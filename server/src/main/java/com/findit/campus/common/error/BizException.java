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

    /**
     * 可安全回给客户端的文案；{@code null} = 用 {@link ErrorCode#message()}。
     *
     * <p>为什么不直接用 {@link #getMessage()}：{@code getMessage()} 是给人看的排障细节
     * （"分类已停用不可选: 证件"、"已关闭的记录不可再修改内容"），把它当响应文案等于把内部细节
     * 和实现措辞钉进对外契约。默认出口刻意只回 {@code ErrorCode} 的稳定文案，
     * 少数契约明确要求"同一 code、不同对外文案"的分支（如 10006 的登录失败）才显式打开这个口子。
     */
    private final String publicMessage;

    public BizException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
        this.data = null;
        this.publicMessage = null;
    }

    public BizException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.data = null;
        this.publicMessage = null;
    }

    public BizException(ErrorCode errorCode, String message, Map<String, Object> data) {
        super(message);
        this.errorCode = errorCode;
        this.data = data;
        this.publicMessage = null;
    }

    private BizException(ErrorCode errorCode, String message, Map<String, Object> data, String publicMessage) {
        super(message);
        this.errorCode = errorCode;
        this.data = data;
        this.publicMessage = publicMessage;
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

    /**
     * 同一 {@link ErrorCode} 需要不同<b>对外</b>文案时用这个（如 10006：未登录="请先登录"，登录失败="学号或密码不正确"）。
     *
     * <p>参数只有一个文案：日志与响应共用它。这类文案本身就是"可以公开的固定措辞"，
     * 不需要再区分内部细节 —— 需要区分时说明该分支不该走这里。
     */
    public static BizException withPublicMessage(ErrorCode errorCode, String publicMessage) {
        return new BizException(errorCode, publicMessage, null, publicMessage);
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    /** 错误附加数据；绝大多数分支为 {@code null}。 */
    public Map<String, Object> data() {
        return data;
    }

    /** 对外文案；{@code null} 表示用 {@link #errorCode()} 的默认文案（见字段注释）。 */
    public String publicMessage() {
        return publicMessage;
    }
}
