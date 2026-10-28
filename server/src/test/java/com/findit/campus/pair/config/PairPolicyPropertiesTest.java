package com.findit.campus.pair.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.findit.campus.pair.engine.PairEngines;

/**
 * 配置集中与 fail-fast（A1 §7.1）。
 *
 * <p>这些用例证明"配置写错就在启动期炸"，而不是等某个用户点进详情才发现阈值不生效。
 * 跨字段规则（unlock &lt; direct、notify ≥ unlock）是注解表达不了的部分，单测价值最高。
 *
 * <p>v2.1（ZHYYKFX-84 校准）起有两套：默认那套（语义引擎）与 {@code overrides.local}
 * （本地关键词引擎）。两套都必须过同一组关系校验 —— 只校验默认那套，覆盖值写反了会在
 * 第一次渲染本地卡片时才炸。
 */
@DisplayName("PairPolicyProperties：阈值与保留期 fail-fast")
class PairPolicyPropertiesTest {

    @Test
    @DisplayName("默认值来自校准：0.95/0.80/0.95/30/0.90（语义引擎尺度）")
    void defaultsMatchCalibration() {
        PairPolicyProperties properties = new PairPolicyProperties();
        properties.validate();
        assertEquals(new BigDecimal("0.95"), properties.getContactDirectThreshold());
        assertEquals(new BigDecimal("0.80"), properties.getContactUnlockThreshold());
        assertEquals(new BigDecimal("0.95"), properties.getCompletedVisibleThreshold());
        assertEquals(30, properties.getCompletedRetentionDays());
        assertEquals(new BigDecimal("0.90"), properties.getNotifyThreshold());
    }

    @Test
    @DisplayName("unlock ≥ direct → 启动失败（否则直看档与可授权档重叠）")
    void unlockMustBeLowerThanDirect() {
        PairPolicyProperties properties = new PairPolicyProperties();
        properties.setContactUnlockThreshold(new BigDecimal("0.95"));
        IllegalStateException ex = assertThrows(IllegalStateException.class, properties::validate);
        assertTrue(ex.getMessage().contains("contact_unlock_threshold"));
    }

    @Test
    @DisplayName("分数越界（<0 或 >1）→ 启动失败，并指出具体键")
    void scoreOutOfRangeFailsFast() {
        PairPolicyProperties negative = new PairPolicyProperties();
        negative.setCompletedVisibleThreshold(new BigDecimal("-0.01"));
        assertThrows(IllegalStateException.class, negative::validate);

        PairPolicyProperties tooBig = new PairPolicyProperties();
        tooBig.setNotifyThreshold(new BigDecimal("1.01"));
        assertThrows(IllegalStateException.class, tooBig::validate);
    }

    @Test
    @DisplayName("保留期必须在 1..3650")
    void retentionDaysRange() {
        PairPolicyProperties zero = new PairPolicyProperties();
        zero.setCompletedRetentionDays(0);
        assertThrows(IllegalStateException.class, zero::validate);

        PairPolicyProperties valid = new PairPolicyProperties();
        valid.setCompletedRetentionDays(31);
        assertDoesNotThrow(valid::validate);
    }

    @Test
    @DisplayName("notify < unlock → 启动失败（通知阈值不能比可授权下界还低）")
    void notifyMustNotBeLowerThanUnlock() {
        PairPolicyProperties properties = new PairPolicyProperties();
        properties.setNotifyThreshold(new BigDecimal("0.50"));
        assertThrows(IllegalStateException.class, properties::validate);
    }

    @Test
    @DisplayName("保留期下界由同一个实例算：now - 30d")
    void cutoffUsesSameRetention() {
        PairPolicyProperties properties = new PairPolicyProperties();
        LocalDateTime now = LocalDateTime.of(2026, 10, 27, 12, 0);
        assertEquals(now.minusDays(30), properties.completedCutoff(now));
    }

    @Test
    @DisplayName("forEngine：LOCAL_KEYWORD 取覆盖值，DEEPSEEK/null 取默认")
    void forEngineSelectsThresholdSet() {
        PairPolicyProperties properties = new PairPolicyProperties();
        properties.getOverrides().getLocal().setContactUnlockThreshold(new BigDecimal("0.45"));
        properties.getOverrides().getLocal().setContactDirectThreshold(new BigDecimal("0.65"));
        properties.getOverrides().getLocal().setNotifyThreshold(new BigDecimal("0.55"));
        properties.getOverrides().getLocal().setCompletedVisibleThreshold(new BigDecimal("0.65"));

        PairThresholds local = properties.forEngine(PairEngines.LOCAL_KEYWORD);
        assertEquals(new BigDecimal("0.45"), local.unlock());
        assertEquals(new BigDecimal("0.65"), local.direct());
        assertEquals(new BigDecimal("0.55"), local.notifyThreshold());
        assertEquals(new BigDecimal("0.65"), local.completedVisible());

        assertEquals(new BigDecimal("0.80"), properties.forEngine(PairEngines.DEEPSEEK).unlock());
        assertEquals(new BigDecimal("0.80"), properties.forEngine(null).unlock(), "未打分记录按默认处理");
    }

    @Test
    @DisplayName("只覆盖 local.unlock：其余三个键回退默认，而不是变成 null/0")
    void unspecifiedOverrideKeysFallBackToDefaults() {
        PairPolicyProperties properties = new PairPolicyProperties();
        properties.getOverrides().getLocal().setContactUnlockThreshold(new BigDecimal("0.45"));
        properties.validate();

        PairThresholds local = properties.localThresholds();
        assertEquals(new BigDecimal("0.45"), local.unlock());
        assertEquals(new BigDecimal("0.95"), local.direct(), "未覆盖 → 回退默认");
        assertEquals(new BigDecimal("0.90"), local.notifyThreshold());
        assertEquals(new BigDecimal("0.95"), local.completedVisible());
    }

    @Test
    @DisplayName("本地覆盖 unlock ≥ direct → 启动失败，错误信息指到 overrides.local 的具体键")
    void localOverrideUnlockMustBeLowerThanLocalDirect() {
        PairPolicyProperties properties = new PairPolicyProperties();
        properties.getOverrides().getLocal().setContactUnlockThreshold(new BigDecimal("0.96"));
        IllegalStateException ex = assertThrows(IllegalStateException.class, properties::validate);
        assertTrue(ex.getMessage().contains("findit.pair.overrides.local.contact_unlock_threshold"),
                "错误信息必须指到覆盖键，而不是让人去翻默认值：" + ex.getMessage());
    }

    @Test
    @DisplayName("本地覆盖 notify < unlock → 启动失败（两套校验都生效）")
    void localOverrideNotifyMustNotBeLowerThanUnlock() {
        PairPolicyProperties properties = new PairPolicyProperties();
        properties.getOverrides().getLocal().setContactUnlockThreshold(new BigDecimal("0.45"));
        properties.getOverrides().getLocal().setNotifyThreshold(new BigDecimal("0.44"));
        IllegalStateException ex = assertThrows(IllegalStateException.class, properties::validate);
        assertTrue(ex.getMessage().contains("findit.pair.overrides.local.notify_threshold"));
    }
}
