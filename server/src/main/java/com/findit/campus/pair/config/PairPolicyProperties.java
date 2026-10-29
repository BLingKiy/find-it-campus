package com.findit.campus.pair.config;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import com.findit.campus.pair.engine.PairEngines;

import jakarta.annotation.PostConstruct;

/**
 * 配对/可见性策略配置的<b>唯一</b>持有者（A1 §7.1，`findit.pair.*`）。
 *
 * <p>为什么必须集中：阈值是"业务口径"，不是实现细节。详情门槛、`/my` 计数、`/api/match` 候选、
 * D1 的扫描若各自读一个常量，改一次口径就要改四处，漏一处就是"详情 404 但卡片还能点"。
 * 这里启动时校验并 <b>fail-fast</b>：配置写错就在启动期炸，不带病运行到第一个用户请求。
 *
 * <p><b>为什么按引擎分两套（v2.1，ZHYYKFX-84 校准结论）</b>：同一对内容，
 * {@code LOCAL_KEYWORD} 与 {@code DEEPSEEK} 的分数不在同一把尺子上 —— 实测 20 对标注样本，
 * 语义引擎正例 0.92–0.98 / 负例 0.00–0.15，本地关键词引擎正例 0.20–0.73 / 负例 0.02–0.46。
 * 共用 0.90/0.75 时，降级链路的所有真实匹配都落在 0.75 以下，用户永远看不到卡片
 * （"阈值偏高、看不到匹配结果"的直接原因）。因此：
 * <ul>
 *   <li>{@code findit.pair.contact_*_threshold} 是<b>默认（语义引擎）</b>那套；</li>
 *   <li>{@code findit.pair.overrides.local.*} 覆盖 {@code LOCAL_KEYWORD} 那套，未配置的键回退默认；</li>
 *   <li>{@link #forEngine(String)} 是调用方取阈值的<b>唯一</b>入口，三档判定与卡片过滤都按 pair 自己的
 *       {@code engine} 取值。混用两套尺度会让"能出卡片"和"能申请解锁"再次打架。</li>
 * </ul>
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

    private static final Logger log = LoggerFactory.getLogger(PairPolicyProperties.class);

    /** 直看档：≥ 此分，双端无需授权即可看对方号码（E1 用；C1 只做配置集中）。默认（语义引擎）值。 */
    private BigDecimal contactDirectThreshold = new BigDecimal("0.95");

    /** 可授权档下界（E1 用；< 此分一律 403）。默认（语义引擎）值。 */
    private BigDecimal contactUnlockThreshold = new BigDecimal("0.80");

    /** COMPLETED 详情门槛：查看者自己的启事与目标记录 pair 分数 ≥ 此值才 200（C1 用）。默认值。 */
    private BigDecimal completedVisibleThreshold = new BigDecimal("0.95");

    /** COMPLETED 保留期（天），从 completed_at 起算；到期不改 status，只是读时窗口关闭（C1 用）。 */
    private int completedRetentionDays = 30;

    /** 写通知的分数下界（D1 用；低于它只计数不通知）。默认（语义引擎）值。 */
    private BigDecimal notifyThreshold = new BigDecimal("0.90");

    /** 按引擎覆盖；未配置的键回退到上面的默认值。 */
    private Overrides overrides = new Overrides();

    /**
     * 跨字段校验（fail-fast）。单字段范围用不到注解：这里要表达的是
     * {@code unlock < direct} 这种<b>关系</b>约束，注解表达不了，而且错误信息要能直接指出该改哪个键。
     *
     * <p><b>两套都要校验</b>：只校验默认那套，覆盖值写反了会在第一次渲染本地卡片时才炸
     * （或者更糟：DIRECT 档判成不可看）。这里对默认、{@code overrides.local}、{@code overrides.deepseek}
     * 各解析一次，逐套校验。
     *
     * @throws IllegalStateException 配置非法（应用启动失败，日志里有具体键名与当前值）
     */
    @PostConstruct
    void validate() {
        validateSet("findit.pair", null, contactDirectThreshold, contactUnlockThreshold,
                notifyThreshold, completedVisibleThreshold);
        resolve(PairEngines.LOCAL_KEYWORD, "findit.pair.overrides.local", overrides.getLocal());
        resolve(PairEngines.DEEPSEEK, "findit.pair.overrides.deepseek", overrides.getDeepseek());
        if (completedRetentionDays < 1 || completedRetentionDays > 3650) {
            throw new IllegalStateException("findit.pair.completed_retention_days(" + completedRetentionDays
                    + ") 必须在 1..3650 之间");
        }
        log.info("pair thresholds default(direct={} unlock={} notify={} completedVisible={}) "
                        + "local(direct={} unlock={} notify={} completedVisible={}) "
                        + "deepseek(direct={} unlock={} notify={} completedVisible={})",
                contactDirectThreshold, contactUnlockThreshold, notifyThreshold, completedVisibleThreshold,
                forEngine(PairEngines.LOCAL_KEYWORD).direct(), forEngine(PairEngines.LOCAL_KEYWORD).unlock(),
                forEngine(PairEngines.LOCAL_KEYWORD).notifyThreshold(), forEngine(PairEngines.LOCAL_KEYWORD).completedVisible(),
                forEngine(PairEngines.DEEPSEEK).direct(), forEngine(PairEngines.DEEPSEEK).unlock(),
                forEngine(PairEngines.DEEPSEEK).notifyThreshold(), forEngine(PairEngines.DEEPSEEK).completedVisible());
    }

    /**
     * 取某个引擎实际生效的阈值：{@code LOCAL_KEYWORD} 用 {@code overrides.local}（其余键回退默认），
     * 其它/未知引擎用默认那套。
     *
     * @param engine {@code t_pair_score.engine}；为 {@code null}（记录尚未打分）时按默认处理
     */
    public PairThresholds forEngine(String engine) {
        if (PairEngines.LOCAL_KEYWORD.equals(engine)) {
            return localThresholds();
        }
        return defaultThresholds();
    }

    /** 默认（语义引擎）那套。 */
    public PairThresholds defaultThresholds() {
        return new PairThresholds(PairThresholds.DEFAULT_ENGINE, contactDirectThreshold,
                contactUnlockThreshold, notifyThreshold, completedVisibleThreshold);
    }

    /** {@code LOCAL_KEYWORD} 那套（已回退默认）。 */
    public PairThresholds localThresholds() {
        Override local = overrides.getLocal();
        return new PairThresholds(PairEngines.LOCAL_KEYWORD,
                coalesce(local.getContactDirectThreshold(), contactDirectThreshold),
                coalesce(local.getContactUnlockThreshold(), contactUnlockThreshold),
                coalesce(local.getNotifyThreshold(), notifyThreshold),
                coalesce(local.getCompletedVisibleThreshold(), completedVisibleThreshold));
    }

    /** {@code DEEPSEEK} 那套（已回退默认）。 */
    public PairThresholds deepseekThresholds() {
        Override deepseek = overrides.getDeepseek();
        return new PairThresholds(PairEngines.DEEPSEEK,
                coalesce(deepseek.getContactDirectThreshold(), contactDirectThreshold),
                coalesce(deepseek.getContactUnlockThreshold(), contactUnlockThreshold),
                coalesce(deepseek.getNotifyThreshold(), notifyThreshold),
                coalesce(deepseek.getCompletedVisibleThreshold(), completedVisibleThreshold));
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

    private PairThresholds resolve(String engine, String prefix, Override override) {
        PairThresholds thresholds = new PairThresholds(engine,
                coalesce(override.getContactDirectThreshold(), contactDirectThreshold),
                coalesce(override.getContactUnlockThreshold(), contactUnlockThreshold),
                coalesce(override.getNotifyThreshold(), notifyThreshold),
                coalesce(override.getCompletedVisibleThreshold(), completedVisibleThreshold));
        validateSet(prefix, override, thresholds.direct(), thresholds.unlock(),
                thresholds.notifyThreshold(), thresholds.completedVisible());
        return thresholds;
    }

    /**
     * 一套阈值的关系校验。错误信息里的键名要能直接对应到 YAML：
     * 覆盖了就用 {@code prefix.key}，没覆盖就用默认键名（提示"这来自默认值"）。
     */
    private static void validateSet(String prefix, Override override,
                                    BigDecimal direct, BigDecimal unlock,
                                    BigDecimal notify, BigDecimal completedVisible) {
        requireScore(key(prefix, override, "contact_direct_threshold",
                override != null && override.getContactDirectThreshold() != null), direct);
        requireScore(key(prefix, override, "contact_unlock_threshold",
                override != null && override.getContactUnlockThreshold() != null), unlock);
        requireScore(key(prefix, override, "notify_threshold",
                override != null && override.getNotifyThreshold() != null), notify);
        requireScore(key(prefix, override, "completed_visible_threshold",
                override != null && override.getCompletedVisibleThreshold() != null), completedVisible);
        if (unlock.compareTo(direct) >= 0) {
            throw new IllegalStateException(key(prefix, override, "contact_unlock_threshold",
                    override != null && override.getContactUnlockThreshold() != null)
                    + "(" + unlock + ") 必须小于 "
                    + key(prefix, override, "contact_direct_threshold",
                    override != null && override.getContactDirectThreshold() != null)
                    + "(" + direct + ")");
        }
        if (notify.compareTo(unlock) < 0) {
            throw new IllegalStateException(key(prefix, override, "notify_threshold",
                    override != null && override.getNotifyThreshold() != null)
                    + "(" + notify + ") 不得小于 "
                    + key(prefix, override, "contact_unlock_threshold",
                    override != null && override.getContactUnlockThreshold() != null)
                    + "(" + unlock + ")");
        }
    }

    private static String key(String prefix, Override override, String field, boolean overridden) {
        if (prefix.equals("findit.pair")) {
            return "findit.pair." + field;
        }
        return overridden ? prefix + "." + field : "findit.pair." + field + "（" + prefix + " 未覆盖，回退默认）";
    }

    private static void requireScore(String key, BigDecimal value) {
        if (value == null || value.compareTo(BigDecimal.ZERO) < 0 || value.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalStateException(key + "(" + value + ") 必须在 0..1 之间");
        }
    }

    private static BigDecimal coalesce(BigDecimal override, BigDecimal fallback) {
        return override == null ? fallback : override;
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

    public Overrides getOverrides() {
        return overrides;
    }

    public void setOverrides(Overrides overrides) {
        this.overrides = overrides == null ? new Overrides() : overrides;
    }

    /** 按引擎覆盖的容器；两个字段都始终非空，避免 YAML 只写一半时出现 null。 */
    public static class Overrides {

        private Override local = new Override();
        private Override deepseek = new Override();

        public Override getLocal() {
            return local;
        }

        public void setLocal(Override local) {
            this.local = local == null ? new Override() : local;
        }

        public Override getDeepseek() {
            return deepseek;
        }

        public void setDeepseek(Override deepseek) {
            this.deepseek = deepseek == null ? new Override() : deepseek;
        }
    }

    /**
     * 一套可选的覆盖值：{@code null} = 没配 = 回退默认。
     *
     * <p>刻意不用 {@link BigDecimal} 的零值表示"没配"：{@code 0.00} 是合法阈值
     * （全放行），必须能和"未配置"区分开。
     */
    public static class Override {

        private BigDecimal contactDirectThreshold;
        private BigDecimal contactUnlockThreshold;
        private BigDecimal notifyThreshold;
        private BigDecimal completedVisibleThreshold;

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

        public BigDecimal getNotifyThreshold() {
            return notifyThreshold;
        }

        public void setNotifyThreshold(BigDecimal notifyThreshold) {
            this.notifyThreshold = notifyThreshold;
        }

        public BigDecimal getCompletedVisibleThreshold() {
            return completedVisibleThreshold;
        }

        public void setCompletedVisibleThreshold(BigDecimal completedVisibleThreshold) {
            this.completedVisibleThreshold = completedVisibleThreshold;
        }
    }
}
