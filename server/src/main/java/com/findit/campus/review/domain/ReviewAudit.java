package com.findit.campus.review.domain;

import java.time.LocalDateTime;

/**
 * {@code t_review_audit} 的一行（append-only）。
 *
 * <p><b>没有原文列</b>：审计回答"谁、何时、对哪条、判了什么、为什么、用什么模型/版本、
 * 多久、多少 token"，不复制 title/description/location。复制正文会让审计表变成第二份
 * 未脱敏语料库，而且随记录编辑无限增长。
 */
public class ReviewAudit {

    private Long id;
    private Long recordId;
    private String action;
    private String decision;
    private String reviewState;
    private String reason;
    private String engine;
    private String model;
    private String promptVersion;
    private Integer latencyMs;
    private Integer promptTokens;
    private Integer completionTokens;
    private String actorType;
    private Long actorId;
    private String requestId;
    private LocalDateTime createdAt;

    public static ReviewAudit ai(long recordId, String decision, String reviewState, String reason,
                                 String engine, String model, String promptVersion,
                                 Integer latencyMs, Integer promptTokens, Integer completionTokens,
                                 String requestId) {
        ReviewAudit audit = new ReviewAudit();
        audit.recordId = recordId;
        audit.action = "REVIEW";
        audit.decision = decision;
        audit.reviewState = reviewState;
        audit.reason = reason;
        audit.engine = engine;
        audit.model = model;
        audit.promptVersion = promptVersion;
        audit.latencyMs = latencyMs;
        audit.promptTokens = promptTokens;
        audit.completionTokens = completionTokens;
        audit.actorType = "AI";
        audit.requestId = requestId;
        return audit;
    }

    public static ReviewAudit manual(long recordId, String action, String decision, String reviewState,
                                     String reason, Long actorId, String requestId) {
        ReviewAudit audit = new ReviewAudit();
        audit.recordId = recordId;
        audit.action = action;
        audit.decision = decision;
        audit.reviewState = reviewState;
        audit.reason = reason;
        audit.engine = "MANUAL";
        audit.actorType = "HUMAN";
        audit.actorId = actorId;
        audit.requestId = requestId;
        return audit;
    }

    public static ReviewAudit rawView(long recordId, Long actorId, String requestId) {
        ReviewAudit audit = new ReviewAudit();
        audit.recordId = recordId;
        audit.action = "RAW_VIEW";
        audit.actorType = "HUMAN";
        audit.actorId = actorId;
        audit.requestId = requestId;
        return audit;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getRecordId() {
        return recordId;
    }

    public void setRecordId(Long recordId) {
        this.recordId = recordId;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getDecision() {
        return decision;
    }

    public void setDecision(String decision) {
        this.decision = decision;
    }

    public String getReviewState() {
        return reviewState;
    }

    public void setReviewState(String reviewState) {
        this.reviewState = reviewState;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getEngine() {
        return engine;
    }

    public void setEngine(String engine) {
        this.engine = engine;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
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

    public String getActorType() {
        return actorType;
    }

    public void setActorType(String actorType) {
        this.actorType = actorType;
    }

    public Long getActorId() {
        return actorId;
    }

    public void setActorId(Long actorId) {
        this.actorId = actorId;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
