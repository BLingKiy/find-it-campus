package com.findit.campus.review.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * 发布闸门配置（{@code findit.review.*}）。<b>默认 {@code ai}</b>（人类 2026-10-29 拍板）：
 * 现场有网有 key，默认就开 AI 审核；{@code off} 只作为回退开关（演示兜底 / 排障），
 * 打开后行为与 v2.0 完全一致。
 *
 * <p>为什么把开关放在这里而不是环境变量：它是产品口径（要不要审核），不是密钥；
 * 配置类集中后 {@code mode} 的非法值会在启动时 fail-fast，不会带病运行到发布请求才 500。
 *
 * <p>{@code max-attempts} 是<b>首次之外的重试次数</b>（人类 2026-10-29 明确：重试 3 次、
 * 退避 1s/2s/4s、单次超时 15s）。因此单条记录最多调用 {@code 1 + max-attempts = 4} 次；
 * 连续失败后行停在 {@code PENDING + review_manual_required=1}，等待<b>发布者本人</b>确认，
 * <b>永远不会自动上架</b>。
 */
@Component
@ConfigurationProperties(prefix = "findit.review")
public class ReviewProperties {

    /** {@code ai} = 规则打码 + LLM 后处理/审核；{@code off} = 整条后处理跳过（v2.0 行为）。 */
    private String mode = "ai";

    /** 首次失败后最多重试几次；总调用次数 = 1 + maxAttempts。 */
    private int maxAttempts = 3;

    /**
     * 第 n 次重试前的等待（毫秒），按序取；默认 1s / 2s / 4s。
     * 列表用尽后沿用最后一项；长度不足 {@code maxAttempts} 时只影响后续档位，不报错。
     */
    private List<Long> retryDelaysMs = List.of(1_000L, 2_000L, 4_000L);

    /** 后处理输出包含 title/description/location/summary/reasons，256 不够。 */
    private int maxTokens = 900;

    /**
     * 单次后处理总超时（含建连与读）。人类 2026-10-29 定为 15s，与 pair 打分的
     * {@code read-timeout} 对齐；输出是整段文本，900 token 在慢网络下可能触顶，
     * 超时即按可重试失败处理，不自动上架。
     */
    private int timeoutMs = 15_000;

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
        if (maxAttempts < 0 || maxAttempts > 10) {
            throw new IllegalStateException("findit.review.max-attempts（重试次数）必须在 0..10");
        }
        if (retryDelaysMs == null || retryDelaysMs.isEmpty()) {
            throw new IllegalStateException("findit.review.retry-delays-ms 不得为空");
        }
        for (Long delay : retryDelaysMs) {
            if (delay == null || delay < 0 || delay > 60_000) {
                throw new IllegalStateException("findit.review.retry-delays-ms 每项必须在 0..60000");
            }
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

    /** 单条记录最多调用 AI 几次 = 首次 + 重试次数。 */
    public int totalAttempts() {
        return maxAttempts + 1;
    }

    /** 第 {@code retryIndex} 次重试（1 起）前的等待毫秒；列表用尽后沿用最后一项。 */
    public long retryDelayMs(int retryIndex) {
        int index = Math.max(0, Math.min(retryIndex - 1, retryDelaysMs.size() - 1));
        return retryDelaysMs.get(index);
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

    public List<Long> getRetryDelaysMs() {
        return retryDelaysMs;
    }

    public void setRetryDelaysMs(List<Long> retryDelaysMs) {
        this.retryDelaysMs = retryDelaysMs;
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
