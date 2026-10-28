package com.findit.campus.review.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * 发布闸门配置（{@code findit.review.*}）。<b>默认 {@code off}</b>：v2.0 行为是回归基线，
 * 打开闸门必须显式改配置 —— 这样既有 45 条验收证据在默认包上仍然成立。
 *
 * <p>为什么把开关放在这里而不是环境变量：它是产品口径（要不要审核），不是密钥；
 * 配置类集中后 {@code mode} 的非法值会在启动时 fail-fast，不会带病运行到发布请求才 500。
 *
 * <p>{@code max-attempts} 是<b>每条记录</b>的总尝试次数（含首次），不是"重试次数"。
 * 连续失败后行停在 {@code PENDING + review_manual_required=1}，<b>永远不会自动上架</b>。
 */
@Component
@ConfigurationProperties(prefix = "findit.review")
public class ReviewProperties {

    /** {@code ai} = 规则打码 + LLM 后处理/审核；{@code off} = 整条后处理跳过（v2.0 行为）。 */
    private String mode = "off";

    /** 每条记录最多尝试几次 AI 审核；超过即转人工。 */
    private int maxAttempts = 3;

    /** 重试指数退避基数（毫秒）：第 n 次失败后等待 base * 2^(n-1)。 */
    private long retryBackoffMs = 300;

    /** 后处理输出包含 title/description/location/summary/reasons，256 不够。 */
    private int maxTokens = 900;

    /**
     * 单次后处理总超时（含建连与读）。比 pair 打分的 20s 大：输出是整段文本，
     * 900 token 在慢网络下 15s 会误判超时；45s 是经验值，需按真实 P95 调整（ADR 已标注）。
     */
    private int timeoutMs = 45_000;

    /** prompt 版本落 {@code t_record.review_prompt_version}，改 prompt 必须同时改它。 */
    private String promptVersion = "pair-postprocess-v1";

    /** {@code summary} 的硬上限（Unicode 码点）。 */
    private int summaryMaxCodePoints = 40;

    /** 调度兜底：多久扫一次卡在 PENDING 的记录（毫秒）。 */
    private long reconcileIntervalMs = 60_000;

    /** 调度兜底每次最多处理多少条，避免一次拉爆 LLM 额度。 */
    private int reconcileBatchSize = 20;

    @PostConstruct
    public void validate() {
        if (!"ai".equalsIgnoreCase(mode) && !"off".equalsIgnoreCase(mode)) {
            throw new IllegalStateException("findit.review.mode 只能是 ai 或 off，当前值: " + mode);
        }
        if (maxAttempts < 1 || maxAttempts > 10) {
            throw new IllegalStateException("findit.review.max-attempts 必须在 1..10");
        }
        if (retryBackoffMs < 0) {
            throw new IllegalStateException("findit.review.retry-backoff-ms 不得为负");
        }
        if (maxTokens <= 0) {
            throw new IllegalStateException("findit.review.max-tokens 必须为正数");
        }
        if (timeoutMs <= 0) {
            throw new IllegalStateException("findit.review.timeout-ms 必须为正数");
        }
        if (promptVersion == null || promptVersion.isBlank()) {
            throw new IllegalStateException("findit.review.prompt-version 不得为空");
        }
        if (summaryMaxCodePoints < 8 || summaryMaxCodePoints > 64) {
            throw new IllegalStateException("findit.review.summary-max-code-points 必须在 8..64");
        }
        if (reconcileIntervalMs < 10_000) {
            throw new IllegalStateException("findit.review.reconcile-interval-ms 不得小于 10000");
        }
        if (reconcileBatchSize < 1 || reconcileBatchSize > 200) {
            throw new IllegalStateException("findit.review.reconcile-batch-size 必须在 1..200");
        }
    }

    public boolean aiEnabled() {
        return "ai".equalsIgnoreCase(mode);
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public long getRetryBackoffMs() {
        return retryBackoffMs;
    }

    public void setRetryBackoffMs(long retryBackoffMs) {
        this.retryBackoffMs = retryBackoffMs;
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(int maxTokens) {
        this.maxTokens = maxTokens;
    }

    public int getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(int timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public String getPromptVersion() {
        return promptVersion;
    }

    public void setPromptVersion(String promptVersion) {
        this.promptVersion = promptVersion;
    }

    public int getSummaryMaxCodePoints() {
        return summaryMaxCodePoints;
    }

    public void setSummaryMaxCodePoints(int summaryMaxCodePoints) {
        this.summaryMaxCodePoints = summaryMaxCodePoints;
    }

    public long getReconcileIntervalMs() {
        return reconcileIntervalMs;
    }

    public void setReconcileIntervalMs(long reconcileIntervalMs) {
        this.reconcileIntervalMs = reconcileIntervalMs;
    }

    public int getReconcileBatchSize() {
        return reconcileBatchSize;
    }

    public void setReconcileBatchSize(int reconcileBatchSize) {
        this.reconcileBatchSize = reconcileBatchSize;
    }
}
