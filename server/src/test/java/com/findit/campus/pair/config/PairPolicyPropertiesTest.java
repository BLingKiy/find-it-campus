package com.findit.campus.pair.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 配置集中与 fail-fast（A1 §7.1）。
 *
 * <p>这些用例证明"配置写错就在启动期炸"，而不是等某个用户点进详情才发现阈值不生效。
 * 跨字段规则（unlock &lt; direct、notify ≥ unlock）是注解表达不了的部分，单测价值最高。
 */
@DisplayName("PairPolicyProperties：阈值与保留期 fail-fast")
class PairPolicyPropertiesTest {

    @Test
    @DisplayName("默认值与 A1 冻结值一致：0.90/0.75/0.90/30/0.75")
    void defaultsMatchContract() {
        PairPolicyProperties properties = new PairPolicyProperties();
        properties.validate();
        assertEquals(new BigDecimal("0.90"), properties.getContactDirectThreshold());
        assertEquals(new BigDecimal("0.75"), properties.getContactUnlockThreshold());
        assertEquals(new BigDecimal("0.90"), properties.getCompletedVisibleThreshold());
        assertEquals(30, properties.getCompletedRetentionDays());
        assertEquals(new BigDecimal("0.75"), properties.getNotifyThreshold());
    }

    @Test
    @DisplayName("unlock ≥ direct → 启动失败（否则直看档与可授权档重叠）")
    void unlockMustBeLowerThanDirect() {
        PairPolicyProperties properties = new PairPolicyProperties();
        properties.setContactUnlockThreshold(new BigDecimal("0.90"));
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
}
