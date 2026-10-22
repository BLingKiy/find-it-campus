package com.findit.campus.common.validation;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.regex.Pattern;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * {@link StrictDateTime} 的校验器：形状先由同字段的 {@code @Pattern} 兜住，这里只判"日历上有没有这一刻"。
 *
 * <p>两个刻意的选择：
 * <ol>
 *   <li><b>形状不匹配时返回 {@code true}</b>：形状错误已经由 {@code @Pattern} 报过，这里再报一次就是同一个输入
 *       两条 fieldErrors，前端红两次、调用方还以为有两个问题；</li>
 *   <li><b>{@link ResolverStyle#STRICT}</b>：默认的 {@code SMART} 会把 {@code 2026-02-30} 纠成 {@code 2026-02-28}，
 *       把"非法输入"变成"静默写错数据"。宁可 400。</li>
 * </ol>
 *
 * <p>格式只解析不格式化，所以没有时区参与，不需要 {@code ZoneId}：{@code LocalDateTime} 在这里就是
 * 「墙上时间」，与 DB 的 {@code DATETIME} 口径一致。
 */
public class StrictDateTimeValidator implements ConstraintValidator<StrictDateTime, String> {

    /** 线程安全（不可变），可以成员级复用。 */
    private DateTimeFormatter formatter;

    private Pattern shape;

    @Override
    public void initialize(StrictDateTime annotation) {
        this.formatter = DateTimeFormatter.ofPattern(annotation.format()).withResolverStyle(ResolverStyle.STRICT);
        this.shape = Pattern.compile(annotation.shape());
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.isBlank()) {
            // 空值归 @NotBlank 管，这里不重复报。
            return true;
        }
        if (!shape.matcher(value).matches()) {
            // 形状归 @Pattern 管，这里不重复报。
            return true;
        }
        try {
            LocalDateTime.parse(value, formatter);
            return true;
        } catch (DateTimeParseException e) {
            // 形状合法但日历非法：13 月 / 40 日 / 25 点 / 2 月 30 日 / 非闰年 2 月 29 日。
            return false;
        }
    }
}
