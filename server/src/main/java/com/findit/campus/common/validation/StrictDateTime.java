package com.findit.campus.common.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/**
 * 时间字符串必须是<b>日历上真实存在</b>的那一时刻（{@code 2026-02-30}、{@code 2026-13-40}、非闰年 2 月 29 日都不合法）。
 *
 * <p>为什么 {@code @Pattern} 不够：正则只能看形状，{@code 13} 月、{@code 40} 日、{@code 25} 点全都符合
 * {@code \d{2}}。真正判"这一天存不存在"必须交给日历解析，而解析一旦放到 service 层且用默认的
 * {@code SMART} 风格，还会把 {@code 2026-02-30} 悄悄纠成 {@code 2026-02-28} 存进库 —— 比报错更糟，
 * 因为调用方以为写入成功。这里用 {@code STRICT}，非法即拒，并且走 {@code @Valid} 的统一出口
 * {@code 10001 + fieldErrors}（契约 §5.3），不再是 {@code 10005/500}。
 *
 * <p>分工（同一字段上三个约束各管一条规则，保证一次输入只产出<b>一条</b> fieldError）：
 * <ul>
 *   <li>{@code @NotBlank} —— 空值</li>
 *   <li>{@code @Pattern} —— 形状（分隔符、位数）</li>
 *   <li>{@code @StrictDateTime} —— 形状已合法时的日历合法性；形状不合法时本约束<b>不报错</b>，交回 {@code @Pattern}</li>
 * </ul>
 */
@Documented
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = StrictDateTimeValidator.class)
public @interface StrictDateTime {

    /**
     * 与同字段 {@code @Pattern} 的 {@code regexp} 保持一致的时间形状。
     *
     * <p>设成必填而不是给默认值：两处一旦写出不同的形状，就会出现"正则放过的值这里不查、正则拒绝的值这里又报一条"
     * 的叠加错误。必填至少逼着调用方显式声明一次，且能直接用 DTO 里的那个常量。
     */
    String shape();

    /**
     * 解析格式。默认与契约 §1 的时间格式等价；<b>{@code STRICT} 下年份必须用 {@code uuuu}（年），
     * 不能用 {@code yyyy}（纪元年），否则连合法值都会解析失败</b>。
     */
    String format() default "uuuu-MM-dd HH:mm:ss";

    String message() default "不是合法的日历时间";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
