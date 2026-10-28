package com.findit.campus.common.log;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 日志字段消毒（P2-3）：换行/控制字符/超长值不得进入日志行。
 *
 * <p>用例直接打攻击载荷，而不是只测"普通字符串不变"：这个类的价值恰恰在异常输入上。
 */
@DisplayName("LogSafe：CR/LF/TAB/控制字符/限长")
class LogSafeTest {

    @Test
    @DisplayName("CR/LF/TAB 被替换，不再产生新日志行")
    void newlinesAreNeutralized() {
        String payload = "demo01\r\n2026-10-28 00:00:00 INFO 登录成功 userId=1\tend";

        String safe = LogSafe.sanitize(payload);

        assertFalse(safe.contains("\r"), "不得保留 CR");
        assertFalse(safe.contains("\n"), "不得保留 LF");
        assertFalse(safe.contains("\t"), "不得保留 TAB");
        assertTrue(safe.startsWith("demo01__2026-10-28"), "分隔符位置应留下可见痕迹，便于排障");
    }

    @Test
    @DisplayName("其它控制字符与 U+2028/U+2029 一律替换")
    void controlCharactersAreNeutralized() {
        String payload = "a\u0000b\u0007c\u007fd\u2028e\u2029f\u0085g";

        String safe = LogSafe.sanitize(payload);

        assertTrue(safe.chars().noneMatch(ch -> Character.isISOControl(ch)));
        assertFalse(safe.contains("\u2028"));
        assertFalse(safe.contains("\u2029"));
    }

    @Test
    @DisplayName("超长值截断并标注原始长度，日志行不会被单个字段撑爆")
    void longValueIsTruncatedWithOriginalLength() {
        String payload = "x".repeat(1000);

        String safe = LogSafe.sanitize(payload);

        assertTrue(safe.startsWith("x".repeat(LogSafe.DEFAULT_MAX_LENGTH)));
        assertTrue(safe.contains("[1000chars]"));
        assertTrue(safe.length() < LogSafe.DEFAULT_MAX_LENGTH + 32, "截断标记本身也必须短");
    }

    @Test
    @DisplayName("null 与普通值：null 有字面量，普通值原样保留")
    void nullAndPlainValues() {
        assertEquals("null", LogSafe.sanitize(null));
        assertEquals("demo01", LogSafe.sanitize("demo01"));
        assertEquals("", LogSafe.sanitize(""));
    }
}
