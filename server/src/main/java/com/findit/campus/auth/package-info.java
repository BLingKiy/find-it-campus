/**
 * 认证与会话上下文（B2）：登录、登出、当前用户、{@code t_user} 只读出口、bcrypt 校验、登录失败计数。
 *
 * <p>数据所有权：本上下文对 {@code t_user} 只有<b>读</b>权限（运行期不写口令、不建账号、不提供注册）。
 * 账号由 {@code db/seed.sql}（演示账号）或后续管理路径写入；B2 明确不开放注册接口。
 *
 * <p>对外只有三条路由（{@code /api/auth/login}、{@code /api/auth/logout}、{@code /api/auth/me}）；
 * 「哪些接口需要登录」的判定不在这里，而在 {@code auth.web.AuthGateFilter}（Servlet Filter，路由之前执行）
 * 与它的规则表 {@code auth.web.AuthPaths}：统一抛/回 10006，避免每个 controller 各写一遍
 * "取 session / 判空 / 抛异常"。
 */
package com.findit.campus.auth;
