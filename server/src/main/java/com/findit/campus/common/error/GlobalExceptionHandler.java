package com.findit.campus.common.error;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.findit.campus.common.api.ApiResponse;
import com.findit.campus.common.api.ErrorCode;
import com.findit.campus.common.api.RequestContext;
import com.findit.campus.common.log.LogSafe;

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

    /**
     * 字段名白名单形态（camelCase / snake_case，限长）：{@code field} 用于前端定位表单项，
     * 而 JSON 路径里的键名来自请求体，不能原样、无限长地写回契约字段。
     */
    private static final Pattern JSON_FIELD_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,63}");

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
        log.warn("[{}] 参数类型不符: field={} value={}", RequestContext.requestId(), field,
                LogSafe.sanitize(String.valueOf(ex.getValue())));
        return fail(ErrorCode.VALIDATION_FAILED, Map.of("fieldErrors", fieldErrors));
    }

    /**
     * 出口 1c：请求体不是合法 JSON / 反序列化失败 / 请求体缺失 → 10001 + <b>至少一条</b> fieldError。
     *
     * <p>为什么不回空数组：契约把 10001 定义为 {@code fieldErrors:[{field,reason}]}（api-contract §1.1、
     * architecture-baseline §6.2），前端把它落到表单项或 toast。给空数组等于"校验失败但没有任何原因可展示"，
     * 用户只看到一句通用 message —— 这正是 DEF 里记的那个 P2。
     *
     * <p>定位口径：语法合法、只是某个字段类型/结构不符时，Jackson 的 {@code getPath()} 能给出 JSON 字段名，
     * 就用它（与 DTO 入参同名，前端可直接定位到输入框）；整段 JSON 不可解析或请求体缺失时字段名统一给
     * {@code body}。reason 只用固定文案：<b>不回显入参原文、不暴露内部类名</b>，避免把请求体里的敏感内容
     * 带进响应体。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Map<String, Object>>> handleNotReadable(HttpMessageNotReadableException ex) {
        com.findit.campus.common.api.FieldError fieldError = notReadableFieldError(ex);
        log.warn("[{}] 请求体不可解析: field={} cause={} detail={}", RequestContext.requestId(),
                fieldError.field(), ex.getMostSpecificCause().getClass().getSimpleName(),
                LogSafe.sanitize(ex.getMessage(), 512));
        return fail(ErrorCode.VALIDATION_FAILED, Map.of("fieldErrors", List.of(fieldError)));
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
        log.info("[{}] 请求路径不存在: {}", RequestContext.requestId(),
                LogSafe.sanitize(ex.getRequestURL()));
        return fail(ErrorCode.RESOURCE_NOT_FOUND, null);
    }

    /** 出口 2b：资源不存在（Boot 3.2+ 静态资源路径的等价出口）→ 10003。 */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Map<String, Object>>> handleNoResource(NoResourceFoundException ex) {
        log.info("[{}] 请求资源不存在: {}", RequestContext.requestId(),
                LogSafe.sanitize(ex.getResourcePath()));
        return fail(ErrorCode.RESOURCE_NOT_FOUND, null);
    }

    /** 出口 3：业务异常 → 携带的错误码（含契约要求的结构化 {@code data}，见 {@link BizException#data()}）。 */
    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResponse<Map<String, Object>>> handleBiz(BizException ex) {
        log.warn("[{}] 业务异常 code={}: {}", RequestContext.requestId(), ex.errorCode().code(), ex.getMessage());
        return fail(ex.errorCode(), ex.publicMessage(), ex.data());
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

    /**
     * 失败出口（可覆盖对外文案）：{@code publicMessage} 为 {@code null} 时回退到 {@link ErrorCode#message()}，
     * 因此所有既有的 {@code BizException.of(...)} 分支行为不变 —— 只有显式用
     * {@link BizException#withPublicMessage} 的分支才会换文案（10006 的登录失败）。
     */
    private static ResponseEntity<ApiResponse<Map<String, Object>>> fail(ErrorCode code, String publicMessage,
                                                                        Map<String, Object> data) {
        ApiResponse<Map<String, Object>> body = publicMessage == null
                ? ApiResponse.fail(code, data)
                : ApiResponse.fail(code, publicMessage, data);
        return new ResponseEntity<>(body, new HttpHeaders(), HttpStatusCode.valueOf(code.httpStatus().value()));
    }

    private static String safeReason(FieldError fieldError) {
        return safeReason(fieldError.getDefaultMessage());
    }

    /**
     * 请求体不可解析时定位到"最可能出错的那个字段"：能定位到 JSON 字段名就给字段，否则退化为 {@code body}。
     * 无论如何都返回一条非空 fieldError —— 空数组是这次要修掉的缺陷，不允许再从这里流出去。
     */
    private static com.findit.campus.common.api.FieldError notReadableFieldError(HttpMessageNotReadableException ex) {
        Throwable cause = ex.getMostSpecificCause();
        if (cause instanceof JsonMappingException mappingEx) {
            String field = lastJsonFieldName(mappingEx);
            if (field != null) {
                return new com.findit.campus.common.api.FieldError(field, "字段值类型或格式不合法");
            }
        }
        if (cause instanceof JsonProcessingException) {
            return new com.findit.campus.common.api.FieldError("body", "请求体不是合法 JSON 对象，请检查语法与结构");
        }
        // 请求体缺失 / 为空 / 声明类型无法读取：同样是入参问题，给可展示的原因而不是空数组
        return new com.findit.campus.common.api.FieldError("body", "请求体不能为空，且必须是 JSON 对象");
    }

    /** JSON 路径末节点（如 {@code occurredAt}）；根节点与数组下标不产生字段名，返回 null 交给 body 兜底。 */
    private static String lastJsonFieldName(JsonMappingException ex) {
        String field = null;
        for (JsonMappingException.Reference reference : ex.getPath()) {
            String name = reference.getFieldName();
            if (name != null && JSON_FIELD_NAME.matcher(name).matches()) {
                field = name;
            }
        }
        return field;
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
