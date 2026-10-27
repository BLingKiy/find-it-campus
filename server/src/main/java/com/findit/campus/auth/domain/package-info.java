/**
 * 认证上下文的领域对象：{@code t_user} 行（{@link com.findit.campus.auth.domain.User}）
 * 与会话身份快照（{@link com.findit.campus.auth.domain.SessionUser}）。
 *
 * <p>这里不放任何 Spring / Servlet 类型：会话读写与 HTTP 语义在 {@code auth.session} 与 {@code auth.web}。
 */
package com.findit.campus.auth.domain;
