package com.findit.campus.pair.domain;

import com.findit.campus.record.domain.Record;

/**
 * 一次 pair 访问的授权视图：当前用户在哪一端、对方是哪一端、本端是否已授权。
 *
 * <p>「同一用户同时拥有两端」时 {@link #viewerEnd()} 固定返回 {@link PairEnd#LOST} —— 这是契约
 * §4.14 / §4.15 的确定性规则，避免同一个请求在不同实现里给出 LOST 或 FOUND 两种答案。
 * 注意 {@code PATCH /api/pairs/{id}} 的 CONFIRM 是例外：它按「调用者拥有的<b>所有</b>端」写，
 * 因为一次请求就能完成双端确认，不需要 side 参数（契约 §4.13）。
 */
public record PairAccess(PairScore pair, Record lost, Record found, long viewerId) {

    public boolean ownsLost() {
        return ownsEnd(PairEnd.LOST);
    }

    public boolean ownsFound() {
        return ownsEnd(PairEnd.FOUND);
    }

    /** null 记录的端不可能被任何会话拥有（迁移期 {@code publisher_id=NULL} 同理）。 */
    public boolean ownsEnd(PairEnd end) {
        Record record = record(end);
        return record != null && record.getPublisherId() != null
                && record.getPublisherId().longValue() == viewerId;
    }

    public boolean isParticipant() {
        return ownsLost() || ownsFound();
    }

    /** 契约确定性规则：双端同主时 viewerSide 固定 LOST；非参与方审计也回退 LOST。 */
    public PairEnd viewerEnd() {
        if (ownsLost()) {
            return PairEnd.LOST;
        }
        if (ownsFound()) {
            return PairEnd.FOUND;
        }
        return PairEnd.LOST;
    }

    public PairEnd targetEnd() {
        return viewerEnd().opposite();
    }

    public Record record(PairEnd end) {
        return end == PairEnd.LOST ? lost : found;
    }

    public Record targetRecord() {
        return record(targetEnd());
    }

    public boolean isUnlocked(PairEnd end) {
        return unlockedAt(end) != null;
    }

    public java.time.LocalDateTime unlockedAt(PairEnd end) {
        return end == PairEnd.LOST ? pair.getLostUnlockedAt() : pair.getFoundUnlockedAt();
    }

    public String unlockNote(PairEnd end) {
        return end == PairEnd.LOST ? pair.getLostUnlockNote() : pair.getFoundUnlockNote();
    }

    public java.time.LocalDateTime confirmedAt(PairEnd end) {
        return end == PairEnd.LOST ? pair.getLostConfirmedAt() : pair.getFoundConfirmedAt();
    }
}
