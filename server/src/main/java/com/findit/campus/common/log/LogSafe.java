package com.findit.campus.common.log;

/**
 * 日志字段消毒（P2-3）：用户可控文本进日志前，去掉换行/控制字符并限长。
 *
 * <p>为什么要单独一个类：日志注入的失败方式不是异常，而是<b>审计记录本身变得不可信</b>。
 * 学号允许任意 1–32 字符（只有 {@code @Size}），查询参数会 URL 解码，两者都可能在 JSON/URL 里
 * 夹带 {@code \n}、{@code \r}、{@code \t}、U+2028/U+2029。攻击者可以借此伪造一整行"登录成功"
 * 或"审计通过"，让 grep 与告警读到假事实。
 *
 * <p>规则：
 * <ul>
 *   <li>{@code CR/LF/TAB} → {@code _}：保留"这里原本有分隔"的痕迹，但不再换行；</li>
 *   <li>其余控制字符（含 U+2028/U+2029 行分隔符）→ {@code ?}；</li>
 *   <li>超过 {@value #DEFAULT_MAX_LENGTH} 字符截断，并附真实长度，避免超长值撑爆日志；</li>
 *   <li>{@code null} → 字面量 {@code "null"}，调用方不必先判空。</li>
 * </ul>
 *
 * <p>代价：日志里的原始值不再逐字保留。排障需要原文时应按 id 查库，而不是从日志反推；
 * 这条取舍是审计可信度换来的。
 */
public final class LogSafe {

    /** 单个日志字段默认上限：够容纳学号/参数值，又不会让一行日志被一个字段独占。 */
    public static final int DEFAULT_MAX_LENGTH = 128;

    private LogSafe() {
    }

    public static String sanitize(String raw) {
        return sanitize(raw, DEFAULT_MAX_LENGTH);
    }

    public static String sanitize(String raw, int maxLength) {
        if (raw == null) {
            return "null";
        }
        int limit = Math.max(1, maxLength);
        StringBuilder out = new StringBuilder(Math.min(raw.length(), limit) + 16);
        int index = 0;
        for (; index < raw.length() && out.length() < limit; index++) {
            char ch = raw.charAt(index);
            if (ch == '\r' || ch == '\n' || ch == '\t') {
                out.append('_');
            } else if (ch == '\u2028' || ch == '\u2029' || Character.isISOControl(ch)) {
                out.append('?');
            } else {
                out.append(ch);
            }
        }
        if (index < raw.length()) {
            out.append("...[").append(raw.length()).append("chars]");
        }
        return out.toString();
    }
}
