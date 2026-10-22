package com.findit.campus.match.domain;

import java.math.BigDecimal;

/**
 * {@code t_match_result} 实体：一次 {@code /api/match} 调用的快照（评估证据链，ADR-007）。
 *
 * <p>它不是业务数据：写入失败只告警、不影响已经算出来的响应（§7.4 / 契约 §8.5）。
 * 因此这个实体只用于写路径，没有任何读接口消费它（5.1 的评估直接查表或用脚本读 JSON）。
 *
 * <p>{@code inputType} 可为 {@code null}：契约 §8.1 允许调用不带 {@code type}（不限类型），
 * 此时不存在"本次要找的类型"这个事实，存 {@code LOST} 或 {@code FOUND} 都是编造 ——
 * 3.1 据此把该列放宽为可空（见 {@code db/schema.sql} 的变更注释）。
 */
public class MatchResult {

    private Long id;
    private String inputText;
    private String inputType;
    private Long inputCategoryId;
    private String impl;
    private int topN;
    private BigDecimal minScore;
    /** {@code candidates} 列的 JSON 文本（候选三元组，与响应体 items 同构但不含展示字段）。 */
    private String candidates;
    private Integer costMs;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getInputText() {
        return inputText;
    }

    public void setInputText(String inputText) {
        this.inputText = inputText;
    }

    public String getInputType() {
        return inputType;
    }

    public void setInputType(String inputType) {
        this.inputType = inputType;
    }

    public Long getInputCategoryId() {
        return inputCategoryId;
    }

    public void setInputCategoryId(Long inputCategoryId) {
        this.inputCategoryId = inputCategoryId;
    }

    public String getImpl() {
        return impl;
    }

    public void setImpl(String impl) {
        this.impl = impl;
    }

    public int getTopN() {
        return topN;
    }

    public void setTopN(int topN) {
        this.topN = topN;
    }

    public BigDecimal getMinScore() {
        return minScore;
    }

    public void setMinScore(BigDecimal minScore) {
        this.minScore = minScore;
    }

    public String getCandidates() {
        return candidates;
    }

    public void setCandidates(String candidates) {
        this.candidates = candidates;
    }

    public Integer getCostMs() {
        return costMs;
    }

    public void setCostMs(Integer costMs) {
        this.costMs = costMs;
    }
}
