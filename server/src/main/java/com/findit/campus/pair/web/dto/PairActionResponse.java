package com.findit.campus.pair.web.dto;

import java.time.LocalDateTime;

import com.findit.campus.common.config.JacksonConfig;

import com.fasterxml.jackson.annotation.JsonFormat;

/** {@code PATCH /api/pairs/{id}} 的成功 data（契约 §4.13）。 */
public class PairActionResponse {

    private Long pairId;
    private String status;
    private Boolean matched;
    private String mySide;
    private Boolean myConfirmed;
    private Boolean otherConfirmed;

    @JsonFormat(pattern = JacksonConfig.DATE_TIME_PATTERN)
    private LocalDateTime lostConfirmedAt;

    @JsonFormat(pattern = JacksonConfig.DATE_TIME_PATTERN)
    private LocalDateTime foundConfirmedAt;

    @JsonFormat(pattern = JacksonConfig.DATE_TIME_PATTERN)
    private LocalDateTime updatedAt;

    public Long getPairId() {
        return pairId;
    }

    public void setPairId(Long pairId) {
        this.pairId = pairId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Boolean getMatched() {
        return matched;
    }

    public void setMatched(Boolean matched) {
        this.matched = matched;
    }

    public String getMySide() {
        return mySide;
    }

    public void setMySide(String mySide) {
        this.mySide = mySide;
    }

    public Boolean getMyConfirmed() {
        return myConfirmed;
    }

    public void setMyConfirmed(Boolean myConfirmed) {
        this.myConfirmed = myConfirmed;
    }

    public Boolean getOtherConfirmed() {
        return otherConfirmed;
    }

    public void setOtherConfirmed(Boolean otherConfirmed) {
        this.otherConfirmed = otherConfirmed;
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

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
