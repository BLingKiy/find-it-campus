package com.findit.campus.pair.engine;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.findit.campus.pair.config.LlmProperties;

/**
 * 极简熔断器（架构 §8.4：连续 5 次失败 / 60s 观察窗，打开 5 分钟）。
 *
 * <p>为什么需要它：DeepSeek 故障时（key 过期、欠费、网络断），每条记录仍会逐对等待 20 秒超时。
 * 一次扫描 500 条记录 × 10 对 = 5000 次超时，等于把后台线程和数据库连接挂死几小时。
 * 熔断打开后直接本地降级，<b>不发请求</b>，把故障隔离在一次扫描之内。
 *
 * <p>为什么是"连续失败"而不是"窗口内比例"：本链路的调用量低（每天几百到几千次），
 * 比例窗口在低样本下会抖得没法用；连续失败次数简单、可解释，且对"整条链路挂了"这种
 * 最常见的故障形态足够敏感。代价是一次偶发 5xx 也会打开 5 分钟 —— 对一条后台打分链路，
 * 这个代价（5 分钟内用本地分）远小于继续打网络。
 *
 * <p><b>三态与恢复事件（ZHYYKFX-88）</b>：{@code CLOSED → OPEN → HALF_OPEN → CLOSED}。
 * 冷却结束不等于"直接全量放行"：先进入 {@code HALF_OPEN} 只放<b>一次</b>探测调用，
 * 探测成功才回到 {@code CLOSED} 并触发一次恢复回调；探测失败重新 {@code OPEN}。
 * 这样 key 恢复的瞬间不会把攒了几小时的降级记录变成一次并发尖峰，同时给"恢复"一个
 * 明确的时刻 —— 历史 {@code DEGRADED} 记录的重算就挂在这个回调上，而不是等下一次定时扫描。
 *
 * <p>代价：{@code HALF_OPEN} 期间的并发请求会被拒（在 {@code DeepSeekPairScorer} 里表现为
 * {@code CIRCUIT_OPEN} 降级）。这是有意的背压，不是缺陷：探测还没证明通路可用之前，
 * 放更多请求出去只会再打开一次熔断。
 *
 * <p>线程安全：{@code synchronized} 足够。它是每进程一个、调用频率低（每对一次），
 * 用 CAS 重写换不来可测量的收益，却更难读懂。恢复回调在锁外触发，避免监听器里的
 * 提交动作（线程池/日志）拖长临界区。
 */
@Component
public class LlmCircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(LlmCircuitBreaker.class);

    /** 熔断状态。{@code HALF_OPEN} 只允许一个探测请求在飞。 */
    public enum State {
        CLOSED,
        OPEN,
        HALF_OPEN
    }

    private final int failureThreshold;
    private final Duration openDuration;

    /**
     * 探测调用的最长占用时间：超过它还没收到成功/失败回报，就允许再放一次探测。
     *
     * <p>为什么需要：所有正常路径都会调用 {@code recordSuccess/recordFailure}，但如果将来有人
     * 在 {@code allowRequest()} 与回报之间加了 {@code return}，熔断会永久停在 HALF_OPEN，
     * 那比直接打开更难排查。用"两次调用总超时"兜住这个只可能由代码缺陷造成的状态。
     */
    private final Duration probeTimeout;
    private final Clock clock;

    /** 恢复监听器：在探测成功（HALF_OPEN → CLOSED）时触发，锁外执行。 */
    private final List<Runnable> recoveryListeners = new CopyOnWriteArrayList<>();

    private State state = State.CLOSED;
    private int consecutiveFailures;
    private Instant openUntil = Instant.MIN;
    private Instant probeStartedAt = Instant.MIN;
    private boolean probeInFlight;

    /**
     * 生产构造：时钟用 {@link Clock#systemUTC()}。
     *
     * <p>显式 {@code @Autowired}：本类有两个构造器（还有一个给单测注入可控时钟），
     * 不标注的话 Spring 会报 "No default constructor found" —— 这个错误在启动时才暴露，
     * 而单测全绿，所以必须有注解把生产构造钉死。
     */
    @Autowired
    public LlmCircuitBreaker(LlmProperties properties) {
        this(properties, Clock.systemUTC());
    }

    /** 供单测注入可控时钟：熔断的验收点之一是"冷却期过后恢复"，不能靠 sleep 5 分钟。 */
    LlmCircuitBreaker(LlmProperties properties, Clock clock) {
        this.failureThreshold = properties.getCircuitFailureThreshold();
        this.openDuration = properties.getCircuitOpenDuration();
        this.probeTimeout = Duration.ofMillis(Math.max(properties.getTotalTimeoutMs() * 2L, 1_000L));
        this.clock = clock;
    }

    /**
     * 当前是否允许发起真实调用。
     *
     * <p>{@code OPEN} 且冷却已过时，本方法会<b>占用</b>唯一一次探测名额并返回 {@code true}；
     * 因此它只能由真正要发请求的调用方使用。只想观测状态请用 {@link #isOpen()} / {@link #state()}。
     */
    public synchronized boolean allowRequest() {
        Instant now = clock.instant();
        switch (state) {
            case CLOSED:
                return true;
            case OPEN:
                if (now.isBefore(openUntil)) {
                    return false;
                }
                state = State.HALF_OPEN;
                probeInFlight = true;
                probeStartedAt = now;
                log.info("LLM 熔断进入 HALF_OPEN：冷却结束，放行 1 次探测调用");
                return true;
            case HALF_OPEN:
            default:
                if (!probeInFlight) {
                    probeInFlight = true;
                    probeStartedAt = now;
                    return true;
                }
                if (!now.isBefore(probeStartedAt.plus(probeTimeout))) {
                    // 探测没有回报（代码缺陷/进程被挂住）：再放一次，避免永久半开
                    probeStartedAt = now;
                    log.warn("LLM 熔断探测超过 {}ms 无回报，重新放行 1 次探测", probeTimeout.toMillis());
                    return true;
                }
                return false;
        }
    }

    public void recordSuccess() {
        boolean recovered;
        synchronized (this) {
            if (state == State.HALF_OPEN) {
                state = State.CLOSED;
                probeInFlight = false;
                consecutiveFailures = 0;
                recovered = true;
            } else {
                consecutiveFailures = 0;
                recovered = false;
            }
        }
        if (recovered) {
            log.info("LLM 熔断恢复：HALF_OPEN 探测成功，状态 CLOSED");
            fireRecovered();
        }
    }

    public void recordFailure() {
        synchronized (this) {
            if (state == State.HALF_OPEN) {
                reopen("HALF_OPEN 探测失败");
                return;
            }
            consecutiveFailures++;
            if (state == State.CLOSED && consecutiveFailures >= failureThreshold) {
                reopen("连续失败 " + consecutiveFailures + " 次");
            }
        }
    }

    private void reopen(String reason) {
        state = State.OPEN;
        openUntil = clock.instant().plus(openDuration);
        probeInFlight = false;
        consecutiveFailures = 0;
        log.warn("LLM 熔断打开：{}，{} 分钟内直接本地降级", reason, openDuration.toMinutes());
    }

    /** 观测值：距离熔断冷却结束还有多少秒（{@code 0} = 未打开或已进入 HALF_OPEN）。 */
    public synchronized long openSecondsRemaining() {
        Instant now = clock.instant();
        if (state != State.OPEN || !now.isBefore(openUntil)) {
            return 0L;
        }
        return Duration.between(now, openUntil).toSeconds();
    }

    /**
     * 只读判断：冷却期内返回 {@code true}。不会占用探测名额，可安全用于"要不要跳过这一轮"。
     */
    public synchronized boolean isOpen() {
        return state == State.OPEN && clock.instant().isBefore(openUntil);
    }

    public synchronized State state() {
        return state;
    }

    /**
     * 注册恢复回调（HALF_OPEN → CLOSED 时触发一次）。
     *
     * <p>回调在调用 {@link #recordSuccess()} 的线程上、锁外执行，<b>必须快速返回</b>：
     * 那里通常是持有数据库命名锁的打分线程。需要跑扫描就提交到线程池，不要同步跑。
     */
    public void addRecoveryListener(Runnable listener) {
        if (listener != null) {
            recoveryListeners.add(listener);
        }
    }

    private void fireRecovered() {
        for (Runnable listener : recoveryListeners) {
            try {
                listener.run();
            } catch (RuntimeException e) {
                // 一个监听器坏掉不能影响主链路，也不能阻断其它监听器
                log.warn("LLM 恢复回调失败 cause={}", e.getMessage());
            }
        }
    }
}
