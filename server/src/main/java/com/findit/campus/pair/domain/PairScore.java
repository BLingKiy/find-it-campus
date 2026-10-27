package com.findit.campus.pair.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * {@code t_pair_score} 实体：<b>一行一对</b>（契约 §4.13 / 架构 §4.2.5）。
 *
 * <p>字段语义按"谁写"分组，改代码时别混：
 * <ul>
 *   <li><b>打分链路写</b>（D1）：{@code score/engine/degradedReason/promptVersion/latencyMs/}
 *       {@code promptTokens/completionTokens/lostHash/foundHash}</li>
 *   <li><b>用户动作写</b>（E1）：{@code status/lostUnlockedAt/foundUnlockedAt/lostUnlockNote/}
 *       {@code foundUnlockNote/lostConfirmedAt/foundConfirmedAt}</li>
 * </ul>
 *
 * <p>这条分界是本条任务书 #2 的核心：重算只更新第一组列，第二组列<b>原样保留</b>。
 * 落点不在注释里，而在 {@code PairScoreMapper.xml} 的 {@code ON DUPLICATE KEY UPDATE} 列清单 ——
 * 那里出现 {@code status} 就是 bug，{@code PairScoreUpsertSqlTest} 会直接失败。
 *
 * <p>{@code status} 只描述<b>这一对内部</b>的进展，没有「已解决」；{@code MATCHED} 是两端
 * {@code confirmed_at} 均非空的派生结果，不是客户端可直传的值。
 */
public class PairScore {

    private Long id;
    private Long lostId;
    private Long foundId;
    private BigDecimal score;
    private String engine;
    private String degradedReason;
    private String promptVersion;
    private Integer latencyMs;
    private Integer promptTokens;
    private Integer completionTokens;
    private String lostHash;
    private String foundHash;
    private LocalDateTime lostUnlockedAt;
    private LocalDateTime foundUnlockedAt;
    private String lostUnlockNote;
    private String foundUnlockNote;
    private LocalDateTime lostConfirmedAt;
    private LocalDateTime foundConfirmedAt;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getLostId() {
        return lostId;
    }

    public void setLostId(Long lostId) {
        this.lostId = lostId;
    }

    public Long getFoundId() {
        return foundId;
    }

    public void setFoundId(Long foundId) {
        this.foundId = foundId;
    }

    public BigDecimal getScore() {
        return score;
    }

    public void setScore(BigDecimal score) {
        this.score = score;
    }

    public String getEngine() {
        return engine;
    }

    public void setEngine(String engine) {
        this.engine = engine;
    }

    public String getDegradedReason() {
        return degradedReason;
    }

    public void setDegradedReason(String degradedReason) {
        this.degradedReason = degradedReason;
    }

    public String getPromptVersion() {
        return promptVersion;
    }

    public void setPromptVersion(String promptVersion) {
        this.promptVersion = promptVersion;
    }

    public Integer getLatencyMs() {
        return latencyMs;
    }

    public void setLatencyMs(Integer latencyMs) {
        this.latencyMs = latencyMs;
    }

    public Integer getPromptTokens() {
        return promptTokens;
    }

    public void setPromptTokens(Integer promptTokens) {
        this.promptTokens = promptTokens;
    }

    public Integer getCompletionTokens() {
        return completionTokens;
    }

    public void setCompletionTokens(Integer completionTokens) {
        this.completionTokens = completionTokens;
    }

    public String getLostHash() {
        return lostHash;
    }

    public void setLostHash(String lostHash) {
        this.lostHash = lostHash;
    }

    public String getFoundHash() {
        return foundHash;
    }

    public void setFoundHash(String foundHash) {
        this.foundHash = foundHash;
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

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
