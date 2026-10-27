package com.findit.campus.auth.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 登录失败计数（契约 §4.9：同一 studentNo+IP 15 分钟内 5 次只记录、不锁定）。
 *
 * <p>时间用可控时钟推进，不 sleep：窗口过期的行为必须能在毫秒级跑完，否则这段逻辑实际上没被测过。
 */
@DisplayName("LoginAttemptTracker 失败计数")
class LoginAttemptTrackerTest {

    private static final String STUDENT_NO = "demo01";
    private static final String IP = "127.0.0.1";

    @Test
    @DisplayName("同一 key 累计到 5 次：计数为 5，且不抛出、不改变任何返回（只打日志）")
    void accumulatesUpToThreshold() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-27T00:00:00Z"));
        LoginAttemptTracker tracker = new LoginAttemptTracker(clock);

        for (int i = 1; i <= LoginAttemptTracker.FAILURE_THRESHOLD; i++) {
            assertThat(tracker.recordFailure(STUDENT_NO, IP)).isEqualTo(i);
        }
        assertThat(tracker.failureCount(STUDENT_NO, IP)).isEqualTo(LoginAttemptTracker.FAILURE_THRESHOLD);
    }

    @Test
    @DisplayName("不同 key 互不影响：换 IP 或换学号都从 1 重新起算")
    void keysAreIndependent() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-27T00:00:00Z"));
        LoginAttemptTracker tracker = new LoginAttemptTracker(clock);

        tracker.recordFailure(STUDENT_NO, IP);
        tracker.recordFailure(STUDENT_NO, IP);

        assertThat(tracker.recordFailure(STUDENT_NO, "10.0.0.2")).isEqualTo(1);
        assertThat(tracker.recordFailure("demo02", IP)).isEqualTo(1);
    }

    @Test
    @DisplayName("15 分钟窗口过后计数归零（窗口是滑动的起点 + 固定长度，不是永久累计）")
    void windowExpires() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-27T00:00:00Z"));
        LoginAttemptTracker tracker = new LoginAttemptTracker(clock);
        tracker.recordFailure(STUDENT_NO, IP);
        tracker.recordFailure(STUDENT_NO, IP);
        assertThat(tracker.failureCount(STUDENT_NO, IP)).isEqualTo(2);

        clock.advance(LoginAttemptTracker.WINDOW.plusSeconds(1));

        assertThat(tracker.failureCount(STUDENT_NO, IP)).isZero();
        assertThat(tracker.recordFailure(STUDENT_NO, IP)).isEqualTo(1);
    }

    @Test
    @DisplayName("登录成功后清空计数")
    void clearResetsCounter() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-27T00:00:00Z"));
        LoginAttemptTracker tracker = new LoginAttemptTracker(clock);
        tracker.recordFailure(STUDENT_NO, IP);

        tracker.clear(STUDENT_NO, IP);

        assertThat(tracker.failureCount(STUDENT_NO, IP)).isZero();
    }

    @Test
    @DisplayName("key 是攻击者可控的：表规模达到上限后先清过期、仍满则整体清空，内存不随请求无限涨")
    void trackedKeysAreBounded() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-27T00:00:00Z"));
        LoginAttemptTracker tracker = new LoginAttemptTracker(clock);

        for (int i = 0; i <= LoginAttemptTracker.MAX_TRACKED_KEYS; i++) {
            tracker.recordFailure("fuzz-" + i, IP);
        }

        assertThat(tracker.trackedKeys()).isLessThanOrEqualTo(LoginAttemptTracker.MAX_TRACKED_KEYS);
    }

    /** 可推进的时钟：只实现 {@link Clock} 的必需方法，测试里不引入第三方时间库。 */
    private static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration duration) {
            this.now = this.now.plus(duration);
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
