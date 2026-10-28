package com.findit.campus.record.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.findit.campus.record.domain.Record;
import com.findit.campus.record.domain.RecordStatus;
import com.findit.campus.record.service.RecordVisibilityPolicy.DetailAccess;

/**
 * 可见性判定表（契约 §4.2 + 补充 #2）。
 *
 * <p>穷举 "三态 × 三种身份 × 保留期内外 × 是否合格对端" 的组合，因为这张表一旦有空格，
 * 就是"某个身份能看到不该看的记录"——那类 bug 在功能测试里几乎不会自己冒出来。
 */
@DisplayName("RecordVisibilityPolicy：三态可见性穷举")
class RecordVisibilityPolicyTest {

    private static final int RETENTION_DAYS = 30;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 27, 12, 0);
    private static final long OWNER = 1L;
    private static final long OTHER = 2L;

    @Test
    @DisplayName("PENDING：所有者明文；匿名/非所有者遮蔽")
    void pendingVisibility() {
        Record record = record(RecordStatus.PENDING, OWNER, null);
        assertEquals(DetailAccess.OWNER_PLAINTEXT, access(record, OWNER, false));
        assertEquals(DetailAccess.MASKED, access(record, OTHER, false));
        assertEquals(DetailAccess.MASKED, access(record, null, false));
    }

    @Test
    @DisplayName("CLOSED：任何身份（含所有者）404")
    void closedIsInvisibleToEveryone() {
        Record record = record(RecordStatus.CLOSED, OWNER, null);
        assertEquals(DetailAccess.NOT_FOUND, access(record, OWNER, true));
        assertEquals(DetailAccess.NOT_FOUND, access(record, OTHER, true));
        assertEquals(DetailAccess.NOT_FOUND, access(record, null, true));
    }

    @Test
    @DisplayName("COMPLETED 保留期内：所有者 404；合格对端 200 遮蔽；其余 404")
    void completedWithinRetention() {
        Record record = record(RecordStatus.COMPLETED, OWNER, NOW.minusDays(3));
        assertEquals(DetailAccess.NOT_FOUND, access(record, OWNER, true), "A9：所有者详情也 404");
        assertEquals(DetailAccess.MASKED, access(record, OTHER, true));
        assertEquals(DetailAccess.NOT_FOUND, access(record, OTHER, false), "无配对/只有 IGNORED");
        assertEquals(DetailAccess.NOT_FOUND, access(record, null, true));
    }

    @Test
    @DisplayName("COMPLETED 到期（含恰好等于边界）：任何身份 404，合格对端也不例外")
    void completedExpired() {
        Record expired = record(RecordStatus.COMPLETED, OWNER, NOW.minusDays(31));
        assertEquals(DetailAccess.NOT_FOUND, access(expired, OTHER, true));
        Record exactlyAtBoundary = record(RecordStatus.COMPLETED, OWNER, NOW.minusDays(RETENTION_DAYS));
        assertEquals(DetailAccess.NOT_FOUND, access(exactlyAtBoundary, OTHER, true),
                "completed_at + 30d > now 才可见；等于边界即到期");
    }

    @Test
    @DisplayName("COMPLETED 缺 completed_at（脏数据）按到期处理，fail-closed")
    void completedWithoutTimestampIsInvisible() {
        Record dirty = record(RecordStatus.COMPLETED, OWNER, null);
        assertEquals(DetailAccess.NOT_FOUND, access(dirty, OTHER, true));
    }

    @Test
    @DisplayName("owner 判定：匿名永不成立；publisherId 为 null 不匹配")
    void ownerRules() {
        assertFalse(RecordVisibilityPolicy.isOwner(record(RecordStatus.PENDING, OWNER, null), null));
        assertFalse(RecordVisibilityPolicy.isOwner(record(RecordStatus.PENDING, null, null), null));
        assertFalse(RecordVisibilityPolicy.isOwner(record(RecordStatus.PENDING, null, null), OTHER));
        assertTrue(RecordVisibilityPolicy.isOwner(record(RecordStatus.PENDING, OWNER, null), OWNER));
    }

    @Test
    @DisplayName("MATCHABLE：PENDING 恒可；COMPLETED 仅保留期内；CLOSED 恒不可")
    void matchablePredicate() {
        assertTrue(RecordVisibilityPolicy.isMatchable(RecordStatus.PENDING, null, RETENTION_DAYS, NOW));
        assertTrue(RecordVisibilityPolicy.isMatchable(RecordStatus.COMPLETED, NOW.minusDays(1),
                RETENTION_DAYS, NOW));
        assertFalse(RecordVisibilityPolicy.isMatchable(RecordStatus.COMPLETED, NOW.minusDays(31),
                RETENTION_DAYS, NOW));
        assertFalse(RecordVisibilityPolicy.isMatchable(RecordStatus.CLOSED, null, RETENTION_DAYS, NOW));
        assertFalse(RecordVisibilityPolicy.isMatchable(null, null, RETENTION_DAYS, NOW));
    }

    private static DetailAccess access(Record record, Long viewerId, boolean qualifyingPeer) {
        return RecordVisibilityPolicy.detailAccess(record, viewerId, qualifyingPeer, RETENTION_DAYS, NOW);
    }

    private static Record record(RecordStatus status, Long publisherId, LocalDateTime completedAt) {
        Record record = new Record();
        record.setId(9L);
        record.setStatus(status);
        record.setPublisherId(publisherId);
        record.setCompletedAt(completedAt);
        return record;
    }
}
