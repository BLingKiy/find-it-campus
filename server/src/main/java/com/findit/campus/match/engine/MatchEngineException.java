package com.findit.campus.match.engine;

import com.findit.campus.common.api.ErrorCode;
import com.findit.campus.common.error.BizException;

/**
 * 引擎侧异常：<b>携带 {@link ErrorCode}</b>，限 30002（不可用）/ 30003（超时）。
 *
 * <p>继承 {@link BizException} 而不是自定义 {@code RuntimeException}：这样它自动走
 * {@code GlobalExceptionHandler} 的同一出口，和 HTTP 状态码的映射保持一处，不需要为引擎再写一套转换器。
 *
 * <p>为什么不在这层做自动重试/降级：匹配是计算型只读调用，重试安全但会带来"同一个请求计费两次、延迟翻倍"，
 * 且降级会污染评估口径（ADR-009）。失败就明确失败。
 */
public class MatchEngineException extends BizException {

    private static final long serialVersionUID = 1L;

    public MatchEngineException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }

    public MatchEngineException(ErrorCode errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
    }

    /** 引擎不可用（网络失败/配额耗尽/未配置）→ 30002。 */
    public static MatchEngineException unavailable(String message, Throwable cause) {
        return new MatchEngineException(ErrorCode.MATCH_ENGINE_UNAVAILABLE, message, cause);
    }

    /** 引擎超时 → 30003。 */
    public static MatchEngineException timeout(String message, Throwable cause) {
        return new MatchEngineException(ErrorCode.MATCH_ENGINE_TIMEOUT, message, cause);
    }
}
