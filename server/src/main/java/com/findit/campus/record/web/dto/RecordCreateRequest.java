package com.findit.campus.record.web.dto;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.List;
import java.util.Map;

import com.findit.campus.common.api.ErrorCode;
import com.findit.campus.common.api.FieldError;
import com.findit.campus.common.error.BizException;
import com.findit.campus.common.validation.NotFutureMinutes;
import com.findit.campus.common.validation.StrictDateTime;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 新增与修改记录的请求体（契约 §5.1；§6 规定 {@code PUT} 的请求体与新增<b>完全同构</b>）。
 *
 * <p>为什么 {@code PUT} 复用同一个类而不是另建 {@code RecordUpdateRequest}：契约已把两者钉成同构，
 * 拆成两个类就要维护两份校验规则，改一处忘一处的结果是"创建时校验、修改时不校验"（或反之）。
 * 基线 §5.2 列了 {@code RecordUpdateRequest}，这条是<b>有意偏离</b>：等两者真的分叉时再拆，
 * 那时拆分是纯新增，没有成本。
 *
 * <p>所有字符串 setter 先 {@code strip()} 再把空串归一成 {@code null}，于是：
 * <ul>
 *   <li>"去首尾空格后判长"这条契约（§5.1）由 {@code @Size} 直接吃到已归一的值</li>
 *   <li>纯空格输入等价于"未填"，报 {@code @NotBlank} 的错而不是悄悄存一个空格进库</li>
 * </ul>
 */
public class RecordCreateRequest {

    /**
     * 时间字符串的<b>形状</b>（契约 §1）：分隔符与位数。形状合法不代表日期存在（{@code 2026-02-30} 形状合法）。
     *
     * <p>抽成常量是为了让 {@code @Pattern} 与 {@link StrictDateTime#shape()} 引用同一个字面量 ——
     * 两处各写一份正则是同类 bug（改了一处忘了另一处）的温床。
     */
    public static final String DATE_TIME_SHAPE = "\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}";

    /**
     * 与契约 §1 的时间格式、{@code DATETIME} 精度三者一致；解析只认这一种写法（格式严格）。
     *
     * <p>两个都不能省：{@code uuuu}（年）而不是 {@code yyyy}（纪元年），否则 {@code STRICT} 下合法值也解析不了；
     * {@code STRICT} 而不是默认 {@code SMART}，否则 {@code 2026-02-30} 会被悄悄纠成 {@code 2026-02-28} 写进库。
     */
    public static final DateTimeFormatter OCCURRED_AT_FORMAT =
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withResolverStyle(ResolverStyle.STRICT);

    @NotBlank(message = "type 不得为空")
    @Pattern(regexp = "LOST|FOUND", message = "type 取值非法：LOST / FOUND")
    private String type;

    @NotBlank(message = "title 不得为空")
    @Size(min = 2, max = 64, message = "title 长度需在 2 到 64 之间")
    private String title;

    @NotBlank(message = "description 不得为空")
    @Size(min = 2, max = 2000, message = "description 长度需在 2 到 2000 之间")
    private String description;

    /** 可空 = 未分类；存在性与启用状态由 service 查库判定（40001 / 40002）。 */
    private Long categoryId;

    @NotBlank(message = "location 不得为空")
    @Size(min = 1, max = 128, message = "location 长度需在 1 到 128 之间")
    private String location;

    @NotBlank(message = "occurredAt 不得为空")
    @Pattern(regexp = DATE_TIME_SHAPE, message = "occurredAt 格式须为 yyyy-MM-dd HH:mm:ss")
    @StrictDateTime(shape = DATE_TIME_SHAPE, message = "occurredAt 不是合法的日期时间（如 2026-02-30 不存在）")
    @NotFutureMinutes(value = 5, message = "occurredAt 不得晚于当前时间（容忍 5 分钟时钟偏差）")
    private String occurredAt;

    /** 选填（Q-01 未拍板，取基线：不强制）。自由格式，不做真实性校验（无登录体系）。 */
    @Size(max = 64, message = "contact 长度不得超过 64")
    @Pattern(regexp = "[0-9A-Za-z\\u4e00-\\u9fa5_()\\-]+", message = "contact 只允许数字、字母、中文与 -_()")
    private String contact;

    @Size(max = 32, message = "publisherName 长度不得超过 32")
    private String publisherName;

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = normalize(type);
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = normalize(title);
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = normalize(description);
    }

    public Long getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(Long categoryId) {
        this.categoryId = categoryId;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = normalize(location);
    }

    public String getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(String occurredAt) {
        this.occurredAt = normalize(occurredAt);
    }

    public String getContact() {
        return contact;
    }

    public void setContact(String contact) {
        this.contact = normalize(contact);
    }

    public String getPublisherName() {
        return publisherName;
    }

    public void setPublisherName(String publisherName) {
        this.publisherName = normalize(publisherName);
    }

    /**
     * 契约 §5.1：业务时间；形状由 {@code @Pattern}、日历合法性由 {@code @StrictDateTime} 保证，这里只做解析。
     *
     * <p><b>这里仍然必须自己兜住解析失败</b>（而不是直接 {@code LocalDateTime.parse}）：校验注解只在
     * MVC 的 {@code @Valid} 出口生效，service 是公开方法，任何内部调用、异步重放、将来新增的入口都可能绕过它。
     * 绕过时抛出的 {@link DateTimeParseException} 会落到 {@code GlobalExceptionHandler} 的兜底出口，
     * 变成 {@code 10005/500} + 一条 ERROR 堆栈 —— 正是 DEF-01 的原始现象。这里把它翻成契约要求的
     * {@code 10001/400}，并把字段名带给调用方，纵深防御的第二层。
     */
    public LocalDateTime occurredAtAsDateTime() {
        if (occurredAt == null) {
            throw invalidOccurredAt("occurredAt 不得为空");
        }
        try {
            return LocalDateTime.parse(occurredAt, OCCURRED_AT_FORMAT);
        } catch (DateTimeParseException e) {
            throw invalidOccurredAt("occurredAt 不是合法的日期时间：" + occurredAt);
        }
    }

    /** 与 {@code @Valid} 出口同形：{@code 10001 + data.fieldErrors[field=occurredAt]}，不走 10005。 */
    private static BizException invalidOccurredAt(String reason) {
        return BizException.of(ErrorCode.VALIDATION_FAILED, "请求参数校验失败",
                Map.of("fieldErrors", List.of(new FieldError("occurredAt", reason))));
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
