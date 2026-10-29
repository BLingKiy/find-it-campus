package com.findit.campus.review.postprocess;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 规则双检：命中即拒绝；楼栋/楼层/区域一级不误伤。
 *
 * <p>失败策略是 fail-closed：宁可多拒一条，也不让手机号/门牌进公示文本。
 */
@DisplayName("SensitiveTextScanner：确定性规则双检")
class SensitiveTextScannerTest {

    @Test
    @DisplayName("手机号（含分隔符）、邮箱、QQ、学号、带标签姓名均命中")
    void identityHits() {
        assertTrue(SensitiveTextScanner.scan("电话 13800000001").contains("手机号/证件号/邮箱/QQ"));
        assertTrue(SensitiveTextScanner.scan("电话 138 0000 0001").contains("手机号/证件号/邮箱/QQ"));
        assertTrue(SensitiveTextScanner.scan("邮箱 a@b.com").contains("邮箱"));
        assertTrue(SensitiveTextScanner.scan("QQ：12345678").contains("QQ号"));
        assertTrue(SensitiveTextScanner.scan("学号 2023123456").contains("学号/工号"));
        assertTrue(SensitiveTextScanner.scan("姓名：张三").contains("姓名"));
    }

    @Test
    @DisplayName("精准门牌/房间/工位/楼栋+数字命中；楼栋/楼层/区域不误伤")
    void preciseLocation() {
        assertTrue(SensitiveTextScanner.scan("3号楼201").contains("精准门牌/房间/座位"));
        assertTrue(SensitiveTextScanner.scan("图书馆 301").contains("精准门牌/房间/座位"));
        assertTrue(SensitiveTextScanner.scan("A12 座").contains("精准门牌/房间/座位"));
        assertTrue(SensitiveTextScanner.scan("工位 35").contains("精准门牌/房间/座位"));
        assertTrue(SensitiveTextScanner.scan("201 教室").contains("精准门牌/房间/座位"));

        assertFalse(SensitiveTextScanner.scan("第三教学楼").contains("精准门牌/房间/座位"));
        assertFalse(SensitiveTextScanner.scan("图书馆三楼").contains("精准门牌/房间/座位"));
        assertFalse(SensitiveTextScanner.scan("南操场").contains("精准门牌/房间/座位"));
    }

    @Test
    @DisplayName("正常日期时间与物品描述不误判")
    void noFalsePositiveOnNormalText() {
        assertTrue(SensitiveTextScanner.scan("2026-10-21 13:30 在图书馆三楼丢了一个黑色皮质钱包").isEmpty());
    }

    @Test
    @DisplayName("发布者展示名精确命中")
    void publisherNameHit() {
        assertTrue(SensitiveTextScanner.scan("李同学捡到一个钱包", "李同学").contains("发布者姓名"));
    }
}
