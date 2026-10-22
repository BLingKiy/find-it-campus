package com.findit.campus.record.web.dto;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import com.findit.campus.common.validation.NotFutureMinutes;

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

    /** 与契约 §1 的时间格式、{@code DATETIME} 精度三者一致；解析只认这一种写法（格式严格）。 */
    public static final DateTimeFormatter OCCURRED_AT_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

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
    @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}", message = "occurredAt 格式须为 yyyy-MM-dd HH:mm:ss")
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

    /** 契约 §5.1：业务时间，格式已由 {@code @Pattern} 保证，这里只做解析。 */
    public LocalDateTime occurredAtAsDateTime() {
        return LocalDateTime.parse(occurredAt, OCCURRED_AT_FORMAT);
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
