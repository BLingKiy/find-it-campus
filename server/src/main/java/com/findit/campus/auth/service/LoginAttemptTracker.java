package com.findit.campus.auth.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.findit.campus.common.log.LogSafe;

/**
 * 登录失败计数：按 {@code studentNo + 客户端 IP} 在 15 分钟窗口内累计，达 5 次只打一条 WARN。
 *
 * <p><b>它不做账号锁定，也不返回 429</b>。契约 §4.9 与任务书都写死了这一点：响应始终是
 * 401 + "学号或密码不正确"，文案与状态码都不随失败次数变化。理由是两条验收直接冲突：
 * "连续 5 次始终 401、文案一致"与"达到阈值后换个响应"不可能同时成立；
 * 真要限流，放在反向代理/IP 层做（不在应用层改契约）。
 *
 * <p>那么计数有什么用：给运维留"有人在撞库"的信号。日志行里带 key（学号+IP）与次数，
 * 门禁/演练时 grep 得到，不需要引入指标系统。
 *
 * <p><b>内存上限</b>：key 由攻击者可控（随便填学号），无上限的 Map 就是一个远程内存放大器。
 * 因此设 {@value #MAX_TRACKED_KEYS} 条上限，达到上限先清过期项、仍然满则整体清空并打 WARN。
 * 计数只是日志素材，丢了不改变鉴权结果，所以"清空"是可接受的降级；内存不涨是硬要求。
 */
@Component
public class LoginAttemptTracker {

    private static final Logger log = LoggerFactory.getLogger(LoginAttemptTracker.class);

    /** 阈值：同一 key 在窗口内累计到这个次数就打 WARN（契约写的示例值就是 5）。 */
    static final int FAILURE_THRESHOLD = 5;

    /** 窗口长度：契约 §4.9「同一 studentNo+IP 15 分钟内失败 5 次」。 */
    static final Duration WINDOW = Duration.ofMinutes(15);

    /** 内存上限：约 1MB 量级（每项 key + 两个字段），防止被随机学号撑爆。 */
    static final int MAX_TRACKED_KEYS = 10_000;

    private final Map<String, FailureWindow> windows = new ConcurrentHashMap<>();
    private final Clock clock;

    public LoginAttemptTracker(Clock clock) {
        this.clock = clock;
    }

    /**
     * 记一次失败。
     *
     * @return 该 key 在当前窗口内的累计失败次数（调用方只用于日志，不参与响应）
     */
    public int recordFailure(String studentNo, String clientIp) {
        String key = key(studentNo, clientIp);
        Instant now = clock.instant();
        admitNewKey(key, now);
        FailureWindow window = windows.compute(key, (ignored, current) -> {
            if (current == null || current.isExpired(now)) {
                return new FailureWindow(now, 1);
            }
            return new FailureWindow(current.startedAt(), current.count() + 1);
        });
        if (window.count() == FAILURE_THRESHOLD) {
            log.warn("登录连续失败达到阈值（仅记录，不锁定账号）: key={} count={} windowMinutes={}",
                    LogSafe.sanitize(key), window.count(), WINDOW.toMinutes());
        }
        return window.count();
    }

    /** 登录成功后清掉该 key 的计数：窗口从下次失败重新起算。 */
    public void clear(String studentNo, String clientIp) {
        windows.remove(key(studentNo, clientIp));
    }

    /** 当前计数（测试与排障用；不暴露给 HTTP 层）。 */
    int failureCount(String studentNo, String clientIp) {
        FailureWindow window = windows.get(key(studentNo, clientIp));
        if (window == null || window.isExpired(clock.instant())) {
            return 0;
        }
        return window.count();
    }

    /** 当前跟踪的 key 数量（内存上限的验证点，见类注释）。 */
    int trackedKeys() {
        return windows.size();
    }

    private void admitNewKey(String key, Instant now) {
        if (windows.containsKey(key) || windows.size() < MAX_TRACKED_KEYS) {
            return;
        }
        windows.entrySet().removeIf(entry -> entry.getValue().isExpired(now));
        if (windows.size() >= MAX_TRACKED_KEYS) {
            log.warn("登录失败计数表达到上限 {}，整体清空（计数只用于日志，不影响鉴权结果）", MAX_TRACKED_KEYS);
            windows.clear();
        }
    }

    private static String key(String studentNo, String clientIp) {
        return studentNo + "|" + clientIp;
    }

    /** 不可变的窗口快照：起点 + 次数，用 {@code compute} 原子替换，避免读写锁。 */
    private record FailureWindow(Instant startedAt, int count) {

        boolean isExpired(Instant now) {
            return !now.isBefore(startedAt.plus(WINDOW));
        }
    }
}
