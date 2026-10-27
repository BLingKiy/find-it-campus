package com.findit.campus.pair.web.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.findit.campus.common.config.JacksonConfig;

import com.fasterxml.jackson.annotation.JsonFormat;

/**
 * {@code GET /api/pairs/{id}/contact} 的 data（契约 §4.14）。
 *
 * <p><b>唯一</b>返回号码的响应体；{@code PairCard} / 列表 / 详情 / 通知都不带 contact 字段。
 * {@code available=false} 时 {@code contact} 必须为 null，{@code reason} 说明原因。
 */
public class ContactView {

    private Long pairId;
    private BigDecimal score;
    private Integer scorePercent;
    private String tier;
    private String viewerSide;
    private String targetSide;
    private Boolean available;
    private String contact;
    private String reason;
    private String message;

    @JsonFormat(pattern = JacksonConfig.DATE_TIME_PATTERN)
    private LocalDateTime unlockedAt;

    private String note;

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

    public Integer getScorePercent() {
        return scorePercent;
    }

    public void setScorePercent(Integer scorePercent) {
        this.scorePercent = scorePercent;
    }

    public String getTier() {
        return tier;
    }

    public void setTier(String tier) {
        this.tier = tier;
    }

    public String getViewerSide() {
        return viewerSide;
    }

    public void setViewerSide(String viewerSide) {
        this.viewerSide = viewerSide;
    }

    public String getTargetSide() {
        return targetSide;
    }

    public void setTargetSide(String targetSide) {
        this.targetSide = targetSide;
    }

    public Boolean getAvailable() {
        return available;
    }

    public void setAvailable(Boolean available) {
        this.available = available;
    }

    public String getContact() {
        return contact;
    }

    public void setContact(String contact) {
        this.contact = contact;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public LocalDateTime getUnlockedAt() {
        return unlockedAt;
    }

    public void setUnlockedAt(LocalDateTime unlockedAt) {
        this.unlockedAt = unlockedAt;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }
}
