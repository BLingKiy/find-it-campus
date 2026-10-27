package com.findit.campus.pair.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.findit.campus.pair.domain.ContactTier;
import com.findit.campus.record.domain.Record;
import com.findit.campus.record.domain.RecordStatus;

/**
 * 三档与 pair 可用性的边界：阈值「等于」必须进上一档，保留期「到期那一刻」必须失效。
 * 这两处是最容易写成 {@code >} / {@code >=} 混用的地方。
 */
@DisplayName("PairAccessPolicy：三档边界 / CLOSED / COMPLETED 保留期")
class PairAccessPolicyTest {

    private static final BigDecimal DIRECT = new BigDecimal("0.90");
    private static final BigDecimal UNLOCK = new BigDecimal("0.75");

    @Test
    @DisplayName("分数恰好 0.90 → DIRECT；0.8999 → AUTHORIZABLE；恰好 0.75 → AUTHORIZABLE；0.7499 → LOW")
    void tierBoundaries() {
        assertEquals(ContactTier.DIRECT, PairAccessPolicy.tier(new BigDecimal("0.90"), DIRECT, UNLOCK));
        assertEquals(ContactTier.AUTHORIZABLE, PairAccessPolicy.tier(new BigDecimal("0.8999"), DIRECT, UNLOCK));
        assertEquals(ContactTier.AUTHORIZABLE, PairAccessPolicy.tier(new BigDecimal("0.75"), DIRECT, UNLOCK));
        assertEquals(ContactTier.LOW, PairAccessPolicy.tier(new BigDecimal("0.7499"), DIRECT, UNLOCK));
        assertEquals(ContactTier.DIRECT, PairAccessPolicy.tier(new BigDecimal("1.0000"), DIRECT, UNLOCK));
        assertEquals(ContactTier.LOW, PairAccessPolicy.tier(null, DIRECT, UNLOCK));
    }

    @Test
    @DisplayName("PENDING 可用；任一端 CLOSED 不可用；COMPLETED 仅在保留期内可用")
    void usability() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 27, 12, 0);
        Record lost = record(RecordStatus.PENDING, null);
        Record found = record(RecordStatus.PENDING, null);
        assertTrue(PairAccessPolicy.isUsable(lost, found, 30, now));

        assertFalse(PairAccessPolicy.isUsable(lost, record(RecordStatus.CLOSED, null), 30, now));
        assertFalse(PairAccessPolicy.isUsable(record(RecordStatus.CLOSED, null), found, 30, now));

        // 2026-10-27 12:00 - 30 天 = 2026-09-27 12:00；边界取「大于」，恰好到期即不可用
        assertTrue(PairAccessPolicy.isUsable(lost,
                record(RecordStatus.COMPLETED, LocalDateTime.of(2026, 9, 27, 12, 0).plusSeconds(1)), 30, now));
        assertFalse(PairAccessPolicy.isUsable(lost,
                record(RecordStatus.COMPLETED, LocalDateTime.of(2026, 9, 27, 12, 0)), 30, now));
        assertFalse(PairAccessPolicy.isUsable(lost, record(RecordStatus.COMPLETED, null), 30, now));
        assertFalse(PairAccessPolicy.isUsable(null, found, 30, now));
    }

    @Test
    @DisplayName("§5.1 contactHint 四选一 / contactAvailable / scorePercent 四舍五入")
    void contactOverlayHelpers() {
        // 未公开优先于「有没有号」：对方没公开时，不该告诉查看者对方填没填号
        assertEquals("对方尚未公开", PairAccessPolicy.contactHint(ContactTier.AUTHORIZABLE, false, true));
        assertEquals("对方尚未公开", PairAccessPolicy.contactHint(ContactTier.AUTHORIZABLE, false, false));
        // 已公开但没填号
        assertEquals("对方未填写联系方式", PairAccessPolicy.contactHint(ContactTier.AUTHORIZABLE, true, false));
        assertEquals("对方已公开联系方式", PairAccessPolicy.contactHint(ContactTier.AUTHORIZABLE, true, true));
        // 直看档不需要授权，但没填号时按钮仍不可点
        assertEquals("匹配度较高，可直接查看", PairAccessPolicy.contactHint(ContactTier.DIRECT, false, true));
        assertEquals("对方未填写联系方式", PairAccessPolicy.contactHint(ContactTier.DIRECT, false, false));
        // LOW 不可达（SQL 已过滤），但若以后放宽过滤，不能给出"可看"的暗示：§4.14 会 403/50002
        assertEquals("对方尚未公开", PairAccessPolicy.contactHint(ContactTier.LOW, true, true));

        assertTrue(PairAccessPolicy.contactAvailable(ContactTier.DIRECT, false, true));
        assertFalse(PairAccessPolicy.contactAvailable(ContactTier.DIRECT, false, false));
        assertTrue(PairAccessPolicy.contactAvailable(ContactTier.AUTHORIZABLE, true, true));
        assertFalse(PairAccessPolicy.contactAvailable(ContactTier.AUTHORIZABLE, false, true));
        assertFalse(PairAccessPolicy.contactAvailable(ContactTier.LOW, true, true));

        assertEquals(92, PairAccessPolicy.scorePercent(new BigDecimal("0.9200")));
        assertEquals(90, PairAccessPolicy.scorePercent(new BigDecimal("0.8999")));
        assertEquals(0, PairAccessPolicy.scorePercent(null));
    }

    private static Record record(RecordStatus status, LocalDateTime completedAt) {
        Record record = new Record();
        record.setStatus(status);
        record.setCompletedAt(completedAt);
        return record;
    }
}
