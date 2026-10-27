package com.findit.campus.pair.config;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * 配对/可见性策略配置的<b>唯一</b>持有者（A1 §7.1，`findit.pair.*`）。
 *
 * <p>为什么必须集中：阈值是"业务口径"，不是实现细节。详情门槛、`/my` 计数、`/api/match` 候选、
 * D1 的扫描若各自读一个常量，改一次口径就要改四处，漏一处就是"详情 404 但卡片还能点"。
 * 这里启动时校验并 <b>fail-fast</b>：配置写错就在启动期炸，不带病运行到第一个用户请求。
 *
 * <p>单位与边界（冻结）：分数 {@code 0..1} 的 {@link BigDecimal}（不用 double，避免 0.9 的二进制误差
 * 让"恰好等于阈值"的 pair 时进时出）；保留期是天数整数。
 *
 * <p>Spring Boot relaxed binding 允许 YAML 用下划线（{@code completed_visible_threshold}），
 * 字段用驼峰。YAML 见 {@code application.yml} 的 {@code findit.pair} 段。
 */
@Component
@ConfigurationProperties(prefix = "findit.pair")
public class PairPolicyProperties {

    /** 直看档：≥ 此分，双端无需授权即可看对方号码（E1 用；C1 只做配置集中）。 */
    private BigDecimal contactDirectThreshold = new BigDecimal("0.90");

    /** 可授权档下界（E1 用；< 此分一律 403）。 */
    private BigDecimal contactUnlockThreshold = new BigDecimal("0.75");

    /** COMPLETED 详情门槛：查看者自己的启事与目标记录 pair 分数 ≥ 此值才 200（C1 用）。 */
    private BigDecimal completedVisibleThreshold = new BigDecimal("0.90");

    /** COMPLETED 保留期（天），从 completed_at 起算；到期不改 status，只是读时窗口关闭（C1 用）。 */
    private int completedRetentionDays = 30;

    /** 写通知的分数下界（D1 用；低于它只计数不通知）。 */
    private BigDecimal notifyThreshold = new BigDecimal("0.75");

    /**
     * 跨字段校验（fail-fast）。单字段范围用不到注解：这里要表达的是
     * {@code unlock < direct} 这种<b>关系</b>约束，注解表达不了，而且错误信息要能直接指出该改哪个键。
     *
     * @throws IllegalStateException 配置非法（应用启动失败，日志里有具体键名与当前值）
     */
    @PostConstruct
    void validate() {
        requireScore("findit.pair.contact_direct_threshold", contactDirectThreshold);
        requireScore("findit.pair.contact_unlock_threshold", contactUnlockThreshold);
        requireScore("findit.pair.completed_visible_threshold", completedVisibleThreshold);
        requireScore("findit.pair.notify_threshold", notifyThreshold);
        if (contactUnlockThreshold.compareTo(contactDirectThreshold) >= 0) {
            throw new IllegalStateException("findit.pair.contact_unlock_threshold(" + contactUnlockThreshold
                    + ") 必须小于 contact_direct_threshold(" + contactDirectThreshold + ")");
        }
        if (notifyThreshold.compareTo(contactUnlockThreshold) < 0) {
            throw new IllegalStateException("findit.pair.notify_threshold(" + notifyThreshold
                    + ") 不得小于 contact_unlock_threshold(" + contactUnlockThreshold + ")");
        }
        if (completedRetentionDays < 1 || completedRetentionDays > 3650) {
            throw new IllegalStateException("findit.pair.completed_retention_days(" + completedRetentionDays
                    + ") 必须在 1..3650 之间");
        }
    }

    /**
     * COMPLETED 保留期的 SQL/Java 下界：{@code now - retentionDays}。
     *
     * <p>所有判定（详情可见性、`/my` 计数、MATCHABLE 召回）都从这里取边界，保证同一个请求里
     * 用的是同一个时间窗口；不要在调用处各写一次 {@code minusDays}。
     */
    public LocalDateTime completedCutoff(LocalDateTime now) {
        return now.minusDays(completedRetentionDays);
    }

    private static void requireScore(String key, BigDecimal value) {
        if (value == null || value.compareTo(BigDecimal.ZERO) < 0 || value.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalStateException(key + "(" + value + ") 必须在 0..1 之间");
        }
    }

    public BigDecimal getContactDirectThreshold() {
        return contactDirectThreshold;
    }

    public void setContactDirectThreshold(BigDecimal contactDirectThreshold) {
        this.contactDirectThreshold = contactDirectThreshold;
    }

    public BigDecimal getContactUnlockThreshold() {
        return contactUnlockThreshold;
    }

    public void setContactUnlockThreshold(BigDecimal contactUnlockThreshold) {
        this.contactUnlockThreshold = contactUnlockThreshold;
    }

    public BigDecimal getCompletedVisibleThreshold() {
        return completedVisibleThreshold;
    }

    public void setCompletedVisibleThreshold(BigDecimal completedVisibleThreshold) {
        this.completedVisibleThreshold = completedVisibleThreshold;
    }

    public int getCompletedRetentionDays() {
        return completedRetentionDays;
    }

    public void setCompletedRetentionDays(int completedRetentionDays) {
        this.completedRetentionDays = completedRetentionDays;
    }

    public BigDecimal getNotifyThreshold() {
        return notifyThreshold;
    }

    public void setNotifyThreshold(BigDecimal notifyThreshold) {
        this.notifyThreshold = notifyThreshold;
    }
}
