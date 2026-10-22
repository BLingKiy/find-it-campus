package com.findit.campus.common.validation;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * {@link NotFutureMinutes} 的校验器。
 *
 * <p>解析失败时<b>判为合法</b>：格式错误由同一字段上的 {@code @Pattern} 报错，这里再报一次
 * 只会让同一条输入产出两条 fieldErrors（"格式不对"+"时间不对"），其中一条是误导。
 */
public class NotFutureMinutesValidator implements ConstraintValidator<NotFutureMinutes, String> {

    /** 与契约 §1 的出入参时间格式、{@code DATETIME} 精度三者一致。 */
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private int toleranceMinutes;

    @Override
    public void initialize(NotFutureMinutes annotation) {
        this.toleranceMinutes = annotation.value();
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.isBlank()) {
            return true;
        }
        LocalDateTime parsed;
        try {
            parsed = LocalDateTime.parse(value, FORMATTER);
        } catch (DateTimeParseException e) {
            return true;
        }
        return !parsed.isAfter(LocalDateTime.now().plusMinutes(toleranceMinutes));
    }
}
