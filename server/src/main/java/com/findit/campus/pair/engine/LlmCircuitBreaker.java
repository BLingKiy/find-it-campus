package com.findit.campus.pair.engine;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

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
 * <p>线程安全：{@code synchronized} 足够。它是每进程一个、调用频率低（每对一次），
 * 用 CAS 重写换不来可测量的收益，却更难读懂。
 */
@Component
public class LlmCircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(LlmCircuitBreaker.class);

    private final int failureThreshold;
    private final Duration openDuration;
    private final Clock clock;

    private int consecutiveFailures;
    private Instant openUntil = Instant.MIN;

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
        this.clock = clock;
    }

    /** 当前是否允许发起真实调用。 */
    public synchronized boolean allowRequest() {
        return clock.instant().isAfter(openUntil) || clock.instant().equals(openUntil);
    }

    public synchronized void recordSuccess() {
        consecutiveFailures = 0;
    }

    public synchronized void recordFailure() {
        consecutiveFailures++;
        if (consecutiveFailures >= failureThreshold && allowRequest()) {
            openUntil = clock.instant().plus(openDuration);
            log.warn("LLM 熔断打开：连续失败 {} 次，{} 分钟内直接本地降级",
                    consecutiveFailures, openDuration.toMinutes());
            consecutiveFailures = 0;
        }
    }

    /** 观测值：距离熔断关闭还有多少秒（{@code 0} = 未打开）。 */
    public synchronized long openSecondsRemaining() {
        Instant now = clock.instant();
        if (!now.isBefore(openUntil)) {
            return 0L;
        }
        return Duration.between(now, openUntil).toSeconds();
    }
}
