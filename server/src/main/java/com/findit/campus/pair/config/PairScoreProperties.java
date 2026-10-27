package com.findit.campus.pair.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * 配对打分任务的运行参数（{@code findit.pair.score.*}）。
 *
 * <p>与 {@link PairPolicyProperties} 的分工：那个类持有<b>业务口径</b>（阈值/保留期，人类拍板、不得改）；
 * 这个类持有<b>工程参数</b>（召回宽度、扫描批量、积压判定、重算冷却），可以由压测结果调整。
 * 把两类值混在一个类里，改性能参数就会动到业务口径的评审面。
 *
 * <p>所有数字都有来源标注：{@code topN=10}、{@code scanLimit=500}、{@code maxLlmCalls=500}
 * 来自架构 §5.4（人类已确认的批量口径）；{@code staleMinutes=10}、{@code degradedRetryMinutes=60}
 * 同为架构给定；{@code preScorePoolSize} 与线程池大小是<b>经验估算，需压测校准</b>。
 */
@Component
@ConfigurationProperties(prefix = "findit.pair.score")
public class PairScoreProperties {

    /** 每条记录召回的反向类型候选数（架构 §5.1：topN=10）。 */
    private int topN = 10;

    /** 召回前的本地预排池上限：只做内存打分，不调 LLM（经验值，需压测校准）。 */
    private int preScorePoolSize = 200;

    /** 单次扫描最多处理多少条记录（架构 §5.4）。 */
    private int scanLimit = 500;

    /** 单次扫描最多发多少次 LLM 调用；达到即停，下一轮继续（架构 §5.4）。 */
    private int maxLlmCalls = 500;

    /** 每日兜底扫描 cron，默认 03:30（架构 §5.4，Asia/Shanghai）。 */
    private String scanCron = "0 30 3 * * *";

    /** 停在 SCORING 超过该分钟数视为崩溃残留，扫描时重置重跑（架构 §5.4）。 */
    private int staleMinutes = 10;

    /** engine=LOCAL_KEYWORD 的行冷却多久后允许重算为 LLM（架构 §5.4：1 小时）。 */
    private int degradedRetryMinutes = 60;

    /** 异步打分线程数：必须小于数据源连接池（每对持一条连接做命名锁）；经验值，需压测校准。 */
    private int workerThreads = 2;

    /** 有界队列长度：满了直接丢弃，由每日扫描补（架构 §5.1「队列满则丢弃」）。 */
    private int workerQueueCapacity = 200;

    @PostConstruct
    public void validate() {
        requirePositive("top-n", topN);
        requirePositive("pre-score-pool-size", preScorePoolSize);
        requirePositive("scan-limit", scanLimit);
        requirePositive("max-llm-calls", maxLlmCalls);
        requirePositive("worker-threads", workerThreads);
        requirePositive("worker-queue-capacity", workerQueueCapacity);
        if (staleMinutes < 1) {
            throw new IllegalStateException("findit.pair.score.stale-minutes 必须 >= 1");
        }
        if (degradedRetryMinutes < 1) {
            throw new IllegalStateException("findit.pair.score.degraded-retry-minutes 必须 >= 1");
        }
        if (scanCron == null || scanCron.isBlank()) {
            throw new IllegalStateException("findit.pair.score.scan-cron 不得为空");
        }
        if (preScorePoolSize < topN) {
            throw new IllegalStateException("findit.pair.score.pre-score-pool-size(" + preScorePoolSize
                    + ") 不得小于 top-n(" + topN + ")");
        }
    }

    private static void requirePositive(String key, int value) {
        if (value <= 0) {
            throw new IllegalStateException("findit.pair.score." + key + " 必须为正数，当前值: " + value);
        }
    }

    public int getTopN() {
        return topN;
    }

    public void setTopN(int topN) {
        this.topN = topN;
    }

    public int getPreScorePoolSize() {
        return preScorePoolSize;
    }

    public void setPreScorePoolSize(int preScorePoolSize) {
        this.preScorePoolSize = preScorePoolSize;
    }

    public int getScanLimit() {
        return scanLimit;
    }

    public void setScanLimit(int scanLimit) {
        this.scanLimit = scanLimit;
    }

    public int getMaxLlmCalls() {
        return maxLlmCalls;
    }

    public void setMaxLlmCalls(int maxLlmCalls) {
        this.maxLlmCalls = maxLlmCalls;
    }

    public String getScanCron() {
        return scanCron;
    }

    public void setScanCron(String scanCron) {
        this.scanCron = scanCron;
    }

    public int getStaleMinutes() {
        return staleMinutes;
    }

    public void setStaleMinutes(int staleMinutes) {
        this.staleMinutes = staleMinutes;
    }

    public int getDegradedRetryMinutes() {
        return degradedRetryMinutes;
    }

    public void setDegradedRetryMinutes(int degradedRetryMinutes) {
        this.degradedRetryMinutes = degradedRetryMinutes;
    }

    public int getWorkerThreads() {
        return workerThreads;
    }

    public void setWorkerThreads(int workerThreads) {
        this.workerThreads = workerThreads;
    }

    public int getWorkerQueueCapacity() {
        return workerQueueCapacity;
    }

    public void setWorkerQueueCapacity(int workerQueueCapacity) {
        this.workerQueueCapacity = workerQueueCapacity;
    }
}
