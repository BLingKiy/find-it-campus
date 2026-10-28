/**
 * 认证的 HTTP 层：三条路由（{@link com.findit.campus.auth.web.AuthController}）、
 * 登录门禁 {@link com.findit.campus.auth.web.AuthGateFilter} 与它的规则表
 * {@link com.findit.campus.auth.web.AuthPaths}。
 *
 * <p>门禁是 Servlet Filter 而不是 MVC 拦截器：它必须在路由解析之前生效，
 * 这样"需要登录但还没实现"的路径对匿名访问也是 401 而不是 404。
 */
package com.findit.campus.auth.web;
