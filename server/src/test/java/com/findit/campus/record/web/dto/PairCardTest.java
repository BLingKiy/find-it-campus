package com.findit.campus.record.web.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.findit.campus.support.PairCardFixtures;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * {@code PairCard} 出口契约（§5.1）：字段同名、文本打码、<b>永远不含 contact</b>。
 *
 * <p>这里用 {@code ObjectMapper} 直接序列化，而不是打 HTTP 接口：要证明的是"这个对象转成 JSON
 * 之后没有号码键、没有号码片段"，与路由/鉴权无关。号码一律用假号 {@code 138/139000000xx}。
 */
@DisplayName("PairCard：§5.1 字段 / 打码 / 无 contact")
class PairCardTest {

    @Test
    @DisplayName("§5.1 全字段逐项映射（含 counterpart 嵌套）")
    void mapsAllContractFields() {
        PairCard card = PairCard.of(PairCardFixtures.direct(23L, 101L));

        assertEquals(101L, card.getPairId());
        assertEquals(0, card.getScore().compareTo(new java.math.BigDecimal("0.9200")));
        assertEquals(92, card.getScorePercent());
        assertEquals("DIRECT", card.getTier());
        assertEquals("MATCHED", card.getStatus());
        assertTrue(card.getMatched());
        assertTrue(card.getMyConfirmed());
        assertTrue(card.getOtherConfirmed());
        assertEquals(java.time.LocalDateTime.of(2026, 10, 27, 9, 0), card.getLostConfirmedAt());
        assertEquals(java.time.LocalDateTime.of(2026, 10, 27, 9, 10), card.getFoundConfirmedAt());
        assertEquals("DEEPSEEK", card.getEngine());
        assertFalse(card.getDegraded());
        assertEquals("LOST", card.getMySide());
        assertFalse(card.getMyUnlocked());
        assertFalse(card.getOtherUnlocked());
        assertNull(card.getMyNote());
        assertNull(card.getOtherNote());
        assertEquals(123L, card.getCounterpart().getRecordId());
        assertEquals("FOUND", card.getCounterpart().getType());
        assertEquals("图书馆拾到黑色钱包", card.getCounterpart().getTitle());
        assertEquals("图书馆三楼自习区", card.getCounterpart().getLocation());
        assertEquals(java.time.LocalDateTime.of(2026, 10, 20, 15, 0), card.getCounterpart().getOccurredAt());
        assertEquals("PENDING", card.getCounterpart().getStatus());
        assertTrue(card.getContactAvailable());
        assertEquals("匹配度较高，可直接查看", card.getContactHint());
    }

    @Test
    @DisplayName("对方标题/地点/两端附言都走 MaskingPolicy；JSON 无 contact 键、无号码片段")
    void masksSensitiveTextAndNeverExposesContact() throws Exception {
        PairCard card = PairCard.of(PairCardFixtures.sensitive(23L, 101L));

        assertEquals("图书馆捡到钱包 [手机号已隐藏]", card.getCounterpart().getTitle());
        assertEquals("第三教学楼…", card.getCounterpart().getLocation());
        assertEquals("周六图书馆见 [手机号已隐藏]", card.getMyNote());
        assertEquals("我在前台 [手机号已隐藏]", card.getOtherNote());

        String json = new ObjectMapper().registerModule(new JavaTimeModule()).writeValueAsString(card);
        assertFalse(json.contains("\"contact\""), "PairCard 永远不带 contact 键：" + json);
        assertFalse(json.matches("(?s).*1[3-9]\\d{9}.*"), "卡片 JSON 不得出现号码片段：" + json);
    }

    @Test
    @DisplayName("degraded 与 otherNote 的取值由适配器决定，DTO 只搬运（null 保持 null）")
    void passThroughNullableFields() {
        PairCard card = PairCard.of(PairCardFixtures.authorizableNotPublic(24L, 102L));

        assertEquals("AUTHORIZABLE", card.getTier());
        assertEquals(80, card.getScorePercent());
        assertEquals("NEW", card.getStatus());
        assertFalse(card.getMatched());
        assertFalse(card.getMyConfirmed());
        assertFalse(card.getOtherConfirmed());
        assertTrue(card.getDegraded());
        assertEquals("LOCAL_KEYWORD", card.getEngine());
        assertFalse(card.getContactAvailable());
        assertEquals("对方尚未公开", card.getContactHint());
        assertNull(card.getLostConfirmedAt());
        assertNull(card.getFoundConfirmedAt());
        assertNull(card.getMyNote());
        assertNull(card.getOtherNote());
    }
}
