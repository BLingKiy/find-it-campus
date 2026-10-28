package com.findit.campus.pair.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * {@code t_access_log} 实体：联系方式查看的审计事实，<b>append-only</b>。
 *
 * <p>本表刻意没有 contact / note / prompt 列 —— 审计只回答「谁在何时看了哪条 pair、结果如何」，
 * 不复制敏感值。把号码抄进审计表等于把一份可读的号码多存一份，且更难清理。
 */
public class AccessLog {

    private Long id;
    private Long pairId;
    private Long viewerId;
    private String viewerSide;
    private String targetSide;
    private BigDecimal score;
    private String result;
    private String requestId;
    private String ip;
    private String userAgent;
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getPairId() {
        return pairId;
    }

    public void setPairId(Long pairId) {
        this.pairId = pairId;
    }

    public Long getViewerId() {
        return viewerId;
    }

    public void setViewerId(Long viewerId) {
        this.viewerId = viewerId;
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

    public BigDecimal getScore() {
        return score;
    }

    public void setScore(BigDecimal score) {
        this.score = score;
    }

    public String getResult() {
        return result;
    }

    public void setResult(String result) {
        this.result = result;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public String getIp() {
        return ip;
    }

    public void setIp(String ip) {
        this.ip = ip;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
