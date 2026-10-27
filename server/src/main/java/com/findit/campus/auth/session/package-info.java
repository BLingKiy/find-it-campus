/**
 * 会话读写唯一入口：登录换 session id、取当前用户、登出清 Cookie（{@link com.findit.campus.auth.session.SessionAuth}）。
 *
 * <p>为什么单独一层而不是塞进 service：拦截器不依赖任何 Spring Bean 就能判"是否登录"，
 * 这让 {@code @WebMvcTest} 切片不需要额外 mock，也让"未登录 → 401"这条路径没有数据库依赖。
 */
package com.findit.campus.auth.session;
