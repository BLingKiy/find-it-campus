/**
 * 认证业务规则：口令校验、会话建立、登录失败计数（{@link com.findit.campus.auth.service.AuthService}、
 * {@link com.findit.campus.auth.service.LoginAttemptTracker}）。
 *
 * <p>事务边界：本上下文运行期<b>只读</b> {@code t_user}，没有写事务。
 */
package com.findit.campus.auth.service;
