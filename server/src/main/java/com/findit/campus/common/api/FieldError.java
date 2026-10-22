package com.findit.campus.common.api;

/**
 * 单个字段的校验失败原因，只出现在 {@link ApiResponse#data} 的 {@code fieldErrors} 数组里。
 *
 * <p>{@code field} 用<b>入参字段名</b>（camelCase，与前端表单控件同名），不用 Java 属性路径
 * 也不用数据库列名 —— 前端要直接拿它定位表单项。
 *
 * @param field  入参字段名（camelCase）
 * @param reason 面向人的中文原因，可直接展示
 */
public record FieldError(String field, String reason) {
}
