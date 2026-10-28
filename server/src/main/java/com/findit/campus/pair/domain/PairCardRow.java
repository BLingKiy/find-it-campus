package com.findit.campus.pair.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * {@code selectCardsByRecordIds} 的一行原始事实（契约 §5.1 的数据来源）。
 *
 * <p>为什么是可变 bean 而不是 record：MyBatis 的构造器映射需要额外配置，而这里列清单由 SQL
 * 别名决定，保持零额外依赖。字段只做「库里有什么」的搬运，<b>所有判定与打码不在这里</b> ——
 * 三档、{@code contactAvailable}/{@code contactHint} 由 {@code PairFactsAdapter} 调
 * {@link com.findit.campus.pair.service.PairAccessPolicy} 计算，遮蔽由 record 侧 DTO 统一做。
 *
 * <p>{@code recordId} 是查询入参里的「本人记录」，不是 pair 主键；同一条 pair 的两端
 * 都属于同一个用户时，这一对会在两个 {@code recordId} 下各出现一次（{@code mySide} 不同）。
 * {@code counterpartContactPresent} 只表达「对方填没填号」，<b>号码本身不进入任何 Java 对象</b>。
 */
public class PairCardRow {

    private Long recordId;
    private Long pairId;
    private BigDecimal score;
    private String status;
    private String engine;
    /** 模型打分依据（≤200 字）；展示前由 {@code PairCard} 过打码。 */
    private String reason;
    private LocalDateTime lostConfirmedAt;
    private LocalDateTime foundConfirmedAt;
    private LocalDateTime lostUnlockedAt;
    private LocalDateTime foundUnlockedAt;
    private String lostUnlockNote;
    private String foundUnlockNote;
    /** {@code LOST} / {@code FOUND}：本人记录在这一对里的端。 */
    private String mySide;
    private Long counterpartRecordId;
    private String counterpartType;
    private String counterpartTitle;
    private String counterpartLocation;
    private LocalDateTime counterpartOccurredAt;
    private LocalDateTime counterpartCreatedAt;
    private String counterpartStatus;
    private boolean counterpartContactPresent;

    public Long getRecordId() {
        return recordId;
    }

    public void setRecordId(Long recordId) {
        this.recordId = recordId;
    }

    public Long getPairId() {
        return pairId;
    }

    public void setPairId(Long pairId) {
        this.pairId = pairId;
    }

    public BigDecimal getScore() {
        return score;
    }

    public void setScore(BigDecimal score) {
        this.score = score;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getEngine() {
        return engine;
    }

    public void setEngine(String engine) {
        this.engine = engine;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public LocalDateTime getLostConfirmedAt() {
        return lostConfirmedAt;
    }

    public void setLostConfirmedAt(LocalDateTime lostConfirmedAt) {
        this.lostConfirmedAt = lostConfirmedAt;
    }

    public LocalDateTime getFoundConfirmedAt() {
        return foundConfirmedAt;
    }

    public void setFoundConfirmedAt(LocalDateTime foundConfirmedAt) {
        this.foundConfirmedAt = foundConfirmedAt;
    }

    public LocalDateTime getLostUnlockedAt() {
        return lostUnlockedAt;
    }

    public void setLostUnlockedAt(LocalDateTime lostUnlockedAt) {
        this.lostUnlockedAt = lostUnlockedAt;
    }

    public LocalDateTime getFoundUnlockedAt() {
        return foundUnlockedAt;
    }

    public void setFoundUnlockedAt(LocalDateTime foundUnlockedAt) {
        this.foundUnlockedAt = foundUnlockedAt;
    }

    public String getLostUnlockNote() {
        return lostUnlockNote;
    }

    public void setLostUnlockNote(String lostUnlockNote) {
        this.lostUnlockNote = lostUnlockNote;
    }

    public String getFoundUnlockNote() {
        return foundUnlockNote;
    }

    public void setFoundUnlockNote(String foundUnlockNote) {
        this.foundUnlockNote = foundUnlockNote;
    }

    public String getMySide() {
        return mySide;
    }

    public void setMySide(String mySide) {
        this.mySide = mySide;
    }

    public Long getCounterpartRecordId() {
        return counterpartRecordId;
    }

    public void setCounterpartRecordId(Long counterpartRecordId) {
        this.counterpartRecordId = counterpartRecordId;
    }

    public String getCounterpartType() {
        return counterpartType;
    }

    public void setCounterpartType(String counterpartType) {
        this.counterpartType = counterpartType;
    }

    public String getCounterpartTitle() {
        return counterpartTitle;
    }

    public void setCounterpartTitle(String counterpartTitle) {
        this.counterpartTitle = counterpartTitle;
    }

    public String getCounterpartLocation() {
        return counterpartLocation;
    }

    public void setCounterpartLocation(String counterpartLocation) {
        this.counterpartLocation = counterpartLocation;
    }

    public LocalDateTime getCounterpartOccurredAt() {
        return counterpartOccurredAt;
    }

    public void setCounterpartOccurredAt(LocalDateTime counterpartOccurredAt) {
        this.counterpartOccurredAt = counterpartOccurredAt;
    }

    public LocalDateTime getCounterpartCreatedAt() {
        return counterpartCreatedAt;
    }

    public void setCounterpartCreatedAt(LocalDateTime counterpartCreatedAt) {
        this.counterpartCreatedAt = counterpartCreatedAt;
    }

    public String getCounterpartStatus() {
        return counterpartStatus;
    }

    public void setCounterpartStatus(String counterpartStatus) {
        this.counterpartStatus = counterpartStatus;
    }

    public boolean isCounterpartContactPresent() {
        return counterpartContactPresent;
    }

    public void setCounterpartContactPresent(boolean counterpartContactPresent) {
        this.counterpartContactPresent = counterpartContactPresent;
    }
}
