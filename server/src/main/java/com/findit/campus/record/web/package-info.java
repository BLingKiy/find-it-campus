/**
 * 记录的 HTTP 层。允许：参数绑定、{@code @Valid} 校验、调 service、映射成 Response DTO。
 *
 * <p>禁止：写业务规则；直接注入 Mapper；返回实体；手填 {@code ApiResponse} 的 code。
 */
package com.findit.campus.record.web;
