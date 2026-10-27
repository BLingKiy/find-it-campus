package com.findit.campus.auth.domain;

import java.io.Serializable;

/**
 * 存进 {@code HttpSession} 的登录身份快照：只放判定权限与回显所需的三个字段。
 *
 * <p>为什么是快照而不是每次请求回查 {@code t_user}：
 * <ul>
 *   <li>每个受保护请求一次 PK 查询，代价不大但纯属为"v2 没有停用入口"这个前提买单；</li>
 *   <li>会话本身就是服务端状态，超时 30 分钟即失效，吊销粒度由会话承担（见 docs/design/v2-auth-session.md）。</li>
 * </ul>
 * <b>代价（已记录，不隐藏）</b>：若将来开放「停用账号」，被停用者的既有会话在超时前仍有效；
 * 到那时要在拦截器加一次 {@code is_active} 复核或引入会话版本号，而不是在这里塞更多字段。
 *
 * <p>实现 {@link Serializable} 不是为了当前的单机内存会话（内存会话不需要序列化），
 * 而是为了让"将来换 Redis/数据库会话"不需要改这个类的形状 —— 现在多写一行，避免将来做破坏性改动。
 *
 * @param id          主键，写路径的 {@code publisher_id} 来源
 * @param studentNo   学号，登录名
 * @param displayName 展示名
 */
public record SessionUser(long id, String studentNo, String displayName) implements Serializable {

    private static final long serialVersionUID = 1L;
}
