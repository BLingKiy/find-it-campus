package com.findit.campus.pair.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * LLM 通路配置（架构 §8.1，键名 {@code findit.llm.*}）。
 *
 * <p><b>这里没有 api-key 字段</b>：密钥只允许从环境变量 {@code DEEPSEEK_API_KEY} 读
 * （见 {@code DeepSeekPairScorer} 的 {@code @Value("${DEEPSEEK_API_KEY:}")}）。
 * 配置类里留一个 key 字段，就等于给"把 key 写进 yml/证据文件"开了一个门缝 —— 这条门缝
 * 比任何一条业务逻辑都更容易在演示前被顺手利用。
 *
 * <p>超时是<b>逐对</b>生效的：每个 pair 打分都新建一次请求并带自己的超时，
 * 不是给整个批次设一个总超时（一个慢 pair 不该把整批拖成降级）。
 */
@Component
@ConfigurationProperties(prefix = "findit.llm")
public class LlmProperties {

    /** OpenAI 兼容端点前缀；实际请求 {@code POST {base-url}/chat/completions}。 */
    private String baseUrl = "https://api.deepseek.com/v1";

    /** 模型名，落证据文件（不落库，库里有 prompt_version）。 */
    private String model = "deepseek-chat";

    /** 建连超时：DNS/TCP/TLS 都要在这段时间内完成。 */
    private int connectTimeoutMs = 3_000;

    /** 读超时：从请求写出到拿到完整响应体。 */
    private int readTimeoutMs = 15_000;

    /** 单次调用总超时（连接 + 读 + 解析）；大于 read 超时用于兜住"读一点就卡住"的场景。 */
    private int totalTimeoutMs = 20_000;

    /** 0.0 = 同一对文本每次打分尽量一致，便于复盘与证据复现。 */
    private double temperature = 0.0;

    /** 只要一个 {@code {"score":…,"reason":…}}，256 足够；调大只会放大成本。 */
    private int maxTokens = 256;

    /** 连续失败达到该次数即打开熔断（架构 §8.4）。 */
    private int circuitFailureThreshold = 5;

    /** 熔断打开时长；期间所有 pair 直接本地降级，不再打网络。 */
    private Duration circuitOpenDuration = Duration.ofMinutes(5);

    @PostConstruct
    public void validate() {
        requireHttpUrl(baseUrl);
        if (model == null || model.isBlank()) {
            throw new IllegalStateException("findit.llm.model 不得为空");
        }
        if (connectTimeoutMs <= 0 || readTimeoutMs <= 0 || totalTimeoutMs <= 0) {
            throw new IllegalStateException("findit.llm.connect/read/total-timeout-ms 必须为正数");
        }
        if (totalTimeoutMs < readTimeoutMs) {
            throw new IllegalStateException("findit.llm.total-timeout-ms(" + totalTimeoutMs
                    + ") 不得小于 read-timeout-ms(" + readTimeoutMs + ")");
        }
        if (maxTokens <= 0) {
            throw new IllegalStateException("findit.llm.max-tokens 必须为正数");
        }
        if (circuitFailureThreshold <= 0) {
            throw new IllegalStateException("findit.llm.circuit-failure-threshold 必须为正数");
        }
    }

    private static void requireHttpUrl(String url) {
        if (url == null || url.isBlank() || !(url.startsWith("http://") || url.startsWith("https://"))) {
            throw new IllegalStateException("findit.llm.base-url 必须是 http(s) 地址，当前值: " + url);
        }
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public int getReadTimeoutMs() {
        return readTimeoutMs;
    }

    public void setReadTimeoutMs(int readTimeoutMs) {
        this.readTimeoutMs = readTimeoutMs;
    }

    public int getTotalTimeoutMs() {
        return totalTimeoutMs;
    }

    public void setTotalTimeoutMs(int totalTimeoutMs) {
        this.totalTimeoutMs = totalTimeoutMs;
    }

    public double getTemperature() {
        return temperature;
    }

    public void setTemperature(double temperature) {
        this.temperature = temperature;
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(int maxTokens) {
        this.maxTokens = maxTokens;
    }

    public int getCircuitFailureThreshold() {
        return circuitFailureThreshold;
    }

    public void setCircuitFailureThreshold(int circuitFailureThreshold) {
        this.circuitFailureThreshold = circuitFailureThreshold;
    }

    public Duration getCircuitOpenDuration() {
        return circuitOpenDuration;
    }

    public void setCircuitOpenDuration(Duration circuitOpenDuration) {
        this.circuitOpenDuration = circuitOpenDuration;
    }
}
