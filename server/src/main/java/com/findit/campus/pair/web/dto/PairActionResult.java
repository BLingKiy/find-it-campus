package com.findit.campus.pair.web.dto;

/**
 * PATCH pair 的结果载体：data 与顶层 message 一起返回。
 *
 * <p>契约 §4.13 要求成功文案（{@code 已联系 / 已确认，等待对方确认 / 已完成双方确认 / 已忽略}）
 * 出现在统一响应体的 {@code message}，而不是 data 里；把两者分开返回，controller 才不会
 * 把业务文案塞进 DTO 变成一个契约外字段。
 */
public record PairActionResult(PairActionResponse data, String message) {
}
