package com.findit.campus.review.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 人类 2026-10-29 拍板的默认值，直接钉在测试上：
 * 默认 {@code ai}、重试 3 次（1s/2s/4s）、单次超时 15s、{@code off} 仍可作为回退。
 *
 * <p>为什么测默认值：这些数字是产品/安全口径，不是"实现细节"。有人把默认改回 {@code off}
 * 或把退避调回 300ms 时，全量测试必须立刻变红，而不是等演示时才发现闸门没开。
 */
@DisplayName("ReviewProperties：默认 ai / 3 次重试 1s2s4s / 15s 超时")
class ReviewPropertiesTest {

    @Test
    @DisplayName("默认 mode=ai，重试 3 次（总调用 4），退避 1s/2s/4s，超时 15s")
    void defaultsMatchHumanDecision() {
        ReviewProperties properties = new ReviewProperties();
        properties.validate();

        assertTrue(properties.aiEnabled(), "默认必须开 AI 审核（human 2026-10-29 拍板）");
        assertEquals("ai", properties.getMode());
        assertEquals(3, properties.getMaxAttempts(), "max-attempts 是重试次数");
        assertEquals(4, properties.totalAttempts(), "总调用 = 首次 + 3 次重试");
        assertEquals(List.of(1_000L, 2_000L, 4_000L), properties.getRetryDelaysMs());
        assertEquals(1_000L, properties.retryDelayMs(1));
        assertEquals(2_000L, properties.retryDelayMs(2));
        assertEquals(4_000L, properties.retryDelayMs(3));
        assertEquals(15_000, properties.getTimeoutMs());
    }

    @Test
    @DisplayName("off 仍是合法回退开关（v2.0 回归基线）")
    void offIsStillSupported() {
        ReviewProperties properties = new ReviewProperties();
        properties.setMode("off");
        properties.validate();

        assertTrue(!properties.aiEnabled());
    }

    @Test
    @DisplayName("非法 mode / 空退避列表在启动时 fail-fast")
    void invalidConfigFailsFast() {
        ReviewProperties badMode = new ReviewProperties();
        badMode.setMode("shadow");
        assertThrows(IllegalStateException.class, badMode::validate);

        ReviewProperties emptyDelays = new ReviewProperties();
        emptyDelays.setRetryDelaysMs(List.of());
        assertThrows(IllegalStateException.class, emptyDelays::validate);
    }
}
