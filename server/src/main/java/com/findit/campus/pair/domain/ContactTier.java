package com.findit.campus.pair.domain;

/**
 * 联系方式三档（契约 §4.14）：
 *
 * <ul>
 *   <li>{@link #DIRECT}：{@code score >= contact_direct_threshold}，双端无需授权即可看；</li>
 *   <li>{@link #AUTHORIZABLE}：{@code contact_unlock_threshold <= score < contact_direct_threshold}，
 *       必须对方端已授权；</li>
 *   <li>{@link #LOW}：{@code score < contact_unlock_threshold}，一律 403/50002，不返回成功体。</li>
 * </ul>
 */
public enum ContactTier {

    DIRECT,
    AUTHORIZABLE,
    LOW
}
