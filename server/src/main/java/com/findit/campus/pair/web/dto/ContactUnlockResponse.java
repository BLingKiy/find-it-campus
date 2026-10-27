package com.findit.campus.pair.web.dto;

import java.time.LocalDateTime;

import com.findit.campus.common.config.JacksonConfig;

import com.fasterxml.jackson.annotation.JsonFormat;

/** {@code POST /api/pairs/{id}/contact-unlock} 的成功 data（契约 §4.15）。 */
public class ContactUnlockResponse {

    private Long pairId;
    private String side;
    private Boolean published;

    @JsonFormat(pattern = JacksonConfig.DATE_TIME_PATTERN)
    private LocalDateTime unlockedAt;

    private String note;

    public Long getPairId() {
        return pairId;
    }

    public void setPairId(Long pairId) {
        this.pairId = pairId;
    }

    public String getSide() {
        return side;
    }

    public void setSide(String side) {
        this.side = side;
    }

    /** 字段名是 {@code public}，Java 关键字不能做属性名，用 {@code published} 承载、JSON 键仍由契约决定。 */
    public Boolean getPublic() {
        return published;
    }

    public void setPublic(Boolean published) {
        this.published = published;
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
