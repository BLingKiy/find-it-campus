package com.findit.campus.pair.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.findit.campus.pair.config.LlmProperties;

/**
 * 熔断（架构 §8.4：连续 5 次失败 / 打开 5 分钟）。
 *
 * <p>用可控时钟而不是 {@code Thread.sleep}：验证"冷却期过后恢复"不该让测试跑 5 分钟，
 * 也不该因为机器慢就变成 flaky。时钟是构造参数，生产代码仍用 {@link Clock#systemUTC()}。
 */
@DisplayName("LlmCircuitBreaker：连续失败打开、冷却后恢复、成功即清零")
class LlmCircuitBreakerTest {

    @Test
    @DisplayName("连续失败到阈值打开；冷却期内一律拒绝；到期后恢复")
    void opensAndRecovers() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-27T10:00:00Z"));
        LlmProperties properties = new LlmProperties();
        properties.setCircuitFailureThreshold(3);
        properties.setCircuitOpenDuration(Duration.ofMinutes(5));
        LlmCircuitBreaker breaker = new LlmCircuitBreaker(properties, clock);

        breaker.recordFailure();
        breaker.recordFailure();
        assertTrue(breaker.allowRequest(), "未到阈值不应打开");

        breaker.recordFailure();
        assertFalse(breaker.allowRequest(), "第 3 次失败后应打开");
        assertEquals(300L, breaker.openSecondsRemaining());

        clock.advance(Duration.ofMinutes(4));
        assertFalse(breaker.allowRequest(), "冷却未结束");

        clock.advance(Duration.ofMinutes(1).plusSeconds(1));
        assertTrue(breaker.allowRequest(), "冷却结束应放行");
        assertEquals(0L, breaker.openSecondsRemaining());
    }

    @Test
    @DisplayName("成功一次即清零：偶发失败不会攒成一次误熔断")
    void successResetsFailures() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-27T10:00:00Z"));
        LlmProperties properties = new LlmProperties();
        properties.setCircuitFailureThreshold(3);
        LlmCircuitBreaker breaker = new LlmCircuitBreaker(properties, clock);

        breaker.recordFailure();
        breaker.recordFailure();
        breaker.recordSuccess();
        breaker.recordFailure();
        breaker.recordFailure();

        assertTrue(breaker.allowRequest(), "清零后再失败 2 次仍未到阈值");
    }

    /** 只有测试用：可推进的时钟。 */
    private static final class MutableClock extends Clock {

        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
