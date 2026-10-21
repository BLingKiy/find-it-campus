package com.findit.campus.common.error;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.findit.campus.common.api.ApiResponse;
import com.findit.campus.common.api.ErrorCode;
import com.findit.campus.common.api.RequestContext;

/**
 * 全局异常处理：唯一失败出口（controller 里不允许 catch 之后自己决定错误码，见 §6）。
 *
 * <p>出口：
 * <ol>
 *   <li>参数类（{@code @Valid} 未过、参数类型不符、请求体不可解析）→ 10001 + fieldErrors（<b>HTTP 400，不是 500</b>）</li>
 *   <li>路由类（路径不存在 → 10003；动词不支持 → 10004，由 Spring 默认解析器经 405 抛出后在此归一）</li>
 *   <li>{@link BizException} → 携带的 ErrorCode 对应 code 与 httpStatus</li>
 *   <li>其余一切 {@code Exception} → 10005，日志打全栈，<b>响应体不含堆栈</b>，只回 message + requestId</li>
 * </ol>
 *
 * <p>为什么必须有第 4 类兜底：漏网的异常若走 Spring 默认出口，会返回 Whitelabel HTML 或
 * {@code {timestamp,status,error}} 这类非契约 body，前端拦截器按 {@code code} 取值直接崩；
 * 这里把它钉成统一形状，并保证 500 的 body 里只有 message 与 requestId（堆栈只进日志）。</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 出口 1：{@code @Valid} 未通过 → 10001 + fieldErrors。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Map<String, Object>>> handleValidation(MethodArgumentNotValidException ex) {
        List<com.findit.campus.common.api.FieldError> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> new com.findit.campus.common.api.FieldError(e.getField(), safeReason(e)))
                .toList();
        log.warn("[{}] 参数校验失败: {}", RequestContext.requestId(), fieldErrors);
        return fail(ErrorCode.VALIDATION_FAILED, Map.of("fieldErrors", fieldErrors));
    }

    /** 出口 1b：路径/查询参数类型不符（如 {@code /api/records/new} 被当 id 请求）→ 10001。 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Map<String, Object>>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        String field = ex.getName() == null ? "id" : ex.getName();
        List<com.findit.campus.common.api.FieldError> fieldErrors =
                List.of(new com.findit.campus.common.api.FieldError(field, "必须为数字"));
        log.warn("[{}] 参数类型不符: field={} value={}", RequestContext.requestId(), field, ex.getValue());
        return fail(ErrorCode.VALIDATION_FAILED, Map.of("fieldErrors", fieldErrors));
    }

    /** 出口 1c：请求体不是合法 JSON / 反序列化失败 → 10001。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Map<String, Object>>> handleNotReadable(HttpMessageNotReadableException ex) {
        log.warn("[{}] 请求体不可解析: {}", RequestContext.requestId(), ex.getMessage());
        return fail(ErrorCode.VALIDATION_FAILED, Map.of("fieldErrors", List.of()));
    }

    /** 出口 1d：请求体超过体积上限 → 10002。 */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Map<String, Object>>> handleTooLarge(MaxUploadSizeExceededException ex) {
        log.warn("[{}] 请求体过大: {}", RequestContext.requestId(), ex.getMessage());
        return fail(ErrorCode.PAYLOAD_TOO_LARGE, null);
    }

    /** 出口 2：路由存在但动词不支持 → 10004（不是 500）。 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Map<String, Object>>> handleMethodNotAllowed(HttpRequestMethodNotSupportedException ex) {
        log.info("[{}] 方法不支持: {}", RequestContext.requestId(), ex.getMethod());
        return fail(ErrorCode.METHOD_NOT_ALLOWED, null);
    }

    /** 出口 2a：路径不存在 → 10003。依赖 {@code spring.mvc.throw-exception-if-no-handler-found=true}。 */
    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<ApiResponse<Map<String, Object>>> handleNoHandler(NoHandlerFoundException ex) {
        log.info("[{}] 请求路径不存在: {}", RequestContext.requestId(), ex.getRequestURL());
        return fail(ErrorCode.RESOURCE_NOT_FOUND, null);
    }

    /** 出口 2b：资源不存在（Boot 3.2+ 静态资源路径的等价出口）→ 10003。 */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Map<String, Object>>> handleNoResource(NoResourceFoundException ex) {
        log.info("[{}] 请求资源不存在: {}", RequestContext.requestId(), ex.getResourcePath());
        return fail(ErrorCode.RESOURCE_NOT_FOUND, null);
    }

    /** 出口 3：业务异常 → 携带的错误码。 */
    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResponse<Map<String, Object>>> handleBiz(BizException ex) {
        log.warn("[{}] 业务异常 code={}: {}", RequestContext.requestId(), ex.errorCode().code(), ex.getMessage());
        return fail(ex.errorCode(), null);
    }

    /** 出口 4：其他一切异常 → 10005，日志打全栈，响应体只给 message + requestId。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Map<String, Object>>> handleUnexpected(Exception ex) {
        String requestId = RequestContext.requestId();
        log.error("[" + requestId + "] 未预期异常", ex);
        return fail(ErrorCode.INTERNAL_ERROR, null);
    }

    private static ResponseEntity<ApiResponse<Map<String, Object>>> fail(ErrorCode code, Map<String, Object> data) {
        ApiResponse<Map<String, Object>> body = ApiResponse.fail(code, data);
        return new ResponseEntity<>(body, new HttpHeaders(), HttpStatusCode.valueOf(code.httpStatus().value()));
    }

    private static String safeReason(FieldError fieldError) {
        String reason = fieldError.getDefaultMessage();
        return reason == null || reason.isBlank() ? "参数不合法" : reason;
    }
}
