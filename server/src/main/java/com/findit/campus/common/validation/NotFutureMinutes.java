package com.findit.campus.common.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/**
 * 时间字符串（{@code yyyy-MM-dd HH:mm:ss}）不得晚于「当前时间 + 容差分钟」。
 *
 * <p>为什么做成注解而不是在 service 里 if 一下：契约 §5.1 把「{@code occurredAt} 不得晚于 now+5min」
 * 和「不得为空」「格式严格」并列为<b>同一个字段的入参校验</b>。校验放 DTO（§6 层职责）可以让它们
 * 走同一条 {@code 10001 + fieldErrors} 出口，前端能在同一个输入框下标红；放到 service 里抛
 * {@code BizException}，错误就只剩一句 message、没有字段名，表单定位不到控件。
 *
 * <p>容差为什么存在：客户端时钟与服务端有偏差，秒级/分钟级的"未来"是噪声不是作弊（Q-08）。
 *
 * <p>{@code null} 视为合法 —— 必填与格式由同一字段上的 {@code @NotBlank} / {@code @Pattern} 负责，
 * 一个约束只表达一条规则，避免同一处输入返回两条互相矛盾的 fieldErrors。
 */
@Documented
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = NotFutureMinutesValidator.class)
public @interface NotFutureMinutes {

    /** 容差分钟数：允许比当前时间晚这么多分钟。 */
    int value() default 0;

    String message() default "时间不得晚于当前时间";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
