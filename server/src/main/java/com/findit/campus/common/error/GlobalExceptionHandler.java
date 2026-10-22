package com.findit.campus.common.error;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;

import com.findit.campus.common.api.ApiResponse;
import com.findit.campus.common.api.ErrorCode;
import com.findit.campus.common.api.RequestContext;

/**
 * 全局异常处理：唯一失败出口（controller 里不允许 catch 之后自己决定错误码，见 §6）。
 *
 * <p>出口：
 * <ol>
 *   <li>参数类（{@code @Valid} 未过、方法级约束未过、参数类型不符、请求体不可解析）
 *       → 10001 + fieldErrors（<b>HTTP 400，不是 500</b>）</li>
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

    /**
     * 出口 1a2：方法级参数校验失败（{@code @RequestParam} / {@code @PathVariable} 上直接挂 {@code @Min}、
     * {@code @Pattern} 等约束）→ 10001 + <b>全量</b> fieldErrors，HTTP 400。
     *
     * <p>为什么必须单独注册：Spring 6.1+ MVC 内建方法校验抛的是 {@link HandlerMethodValidationException}
     * （继承 {@code ResponseStatusException}）。它不被出口 1（只认 {@code @Valid} 请求体）覆盖，
     * 落不到这里就会掉进出口 4 变 10005/500，或由 {@code ResponseStatusExceptionResolver}
     * 渲染成 ProblemDetail —— 两种都不是契约形状，前端按 {@code code} 取值直接崩。
     */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiResponse<Map<String, Object>>> handleMethodValidation(HandlerMethodValidationException ex) {
        List<com.findit.campus.common.api.FieldError> fieldErrors = new ArrayList<>();
        for (ParameterValidationResult result : ex.getParameterValidationResults()) {
            String field = resolveParameterName(result.getMethodParameter());
            List<? extends MessageSourceResolvable> reasons = result.getResolvableErrors();
            if (reasons.isEmpty()) {
                fieldErrors.add(new com.findit.campus.common.api.FieldError(field, "参数不合法"));
                continue;
            }
            reasons.forEach(reason ->
                    fieldErrors.add(new com.findit.campus.common.api.FieldError(field, safeReason(reason.getDefaultMessage()))));
        }
        log.warn("[{}] 方法参数校验失败: {}", RequestContext.requestId(), fieldErrors);
        return fail(ErrorCode.VALIDATION_FAILED, Map.of("fieldErrors", fieldErrors));
    }

    /**
     * 出口 1a1b：{@code @ModelAttribute} 查询对象（如 3.1 的 {@code RecordQuery}）绑定后校验失败 → 10001 + fieldErrors。
     *
     * <p>为什么必须补它：请求体（{@code @RequestBody}）与方法级（{@code @RequestParam}）各自的异常出口已在上文，
     * 但把一组查询参数收成 POJO 后，Spring 抛的是 {@link BindException} —— 它是
     * {@link MethodArgumentNotValidException} 的<b>父类</b>，出口 1 不认它，缺这条就会掉进出口 4
     * 变成 10005/500，而契约 §3.3 要求分页/枚举非法回 10001。
     * 同时注册父子两个出口是安全的：Spring 取最具体的那个，请求体仍走出口 1。
     */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<ApiResponse<Map<String, Object>>> handleBind(BindException ex) {
        List<com.findit.campus.common.api.FieldError> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> new com.findit.campus.common.api.FieldError(e.getField(), safeReason(e)))
                .toList();
        log.warn("[{}] 查询参数校验失败: {}", RequestContext.requestId(), fieldErrors);
        return fail(ErrorCode.VALIDATION_FAILED, Map.of("fieldErrors", fieldErrors));
    }

    /**
     * 出口 1a3：类级 {@code @Validated} + AOP 代理下的方法校验 → 10001 + 全量 fieldErrors，HTTP 400。
     *
     * <p>与出口 1a2 是同一件事的两副面孔：装配方式换成 {@code @Validated} 后，约束由
     * {@code MethodValidationPostProcessor} 抛出 {@link ConstraintViolationException} 而非
     * {@link HandlerMethodValidationException}。两个都注册，避免"换种写法就静默退化成 500"。
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Map<String, Object>>> handleConstraintViolation(ConstraintViolationException ex) {
        List<com.findit.campus.common.api.FieldError> fieldErrors = ex.getConstraintViolations().stream()
                .map(violation -> new com.findit.campus.common.api.FieldError(
                        lastPathNode(violation.getPropertyPath()), safeReason(violation.getMessage())))
                .toList();
        log.warn("[{}] 方法参数校验失败（@Validated）: {}", RequestContext.requestId(), fieldErrors);
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

    /** 出口 3：业务异常 → 携带的错误码（含契约要求的结构化 {@code data}，见 {@link BizException#data()}）。 */
    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResponse<Map<String, Object>>> handleBiz(BizException ex) {
        log.warn("[{}] 业务异常 code={}: {}", RequestContext.requestId(), ex.errorCode().code(), ex.getMessage());
        return fail(ex.errorCode(), ex.data());
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
        return safeReason(fieldError.getDefaultMessage());
    }

    private static String safeReason(String reason) {
        return reason == null || reason.isBlank() ? "参数不合法" : reason;
    }

    /** 入参名取不到（未带 {@code -parameters} 且无调试信息）时退化为 {@code arg{index}}，不返回 null。 */
    private static String resolveParameterName(MethodParameter parameter) {
        String name = parameter.getParameterName();
        return name == null || name.isBlank() ? "arg" + parameter.getParameterIndex() : name;
    }

    /** {@code list.page} → {@code page}：方法校验的 propertyPath 末节点才是入参名，与前端表单项同名。 */
    private static String lastPathNode(Path propertyPath) {
        String name = null;
        for (Path.Node node : propertyPath) {
            if (node.getName() != null) {
                name = node.getName();
            }
        }
        return name == null ? "arg" : name;
    }
}
