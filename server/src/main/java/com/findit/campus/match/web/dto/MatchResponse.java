package com.findit.campus.match.web.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * {@code POST /api/match} 的出参（契约 §8.2）。
 *
 * <p>{@code total} 与 {@code items.length} 恒等（冗余但省得前端算长度，且与分页的 total 同词同义）。
 * {@code matchId} 是 {@code t_match_result} 的主键 —— 契约标注的<b>不可逆点②</b>：
 * 前端一旦使用它（"这条匹配结果"的跳转/申诉入口）就再也去不掉，因此正常路径下每次调用都有值。
 * 仅在快照写失败时为 {@code null}（契约 §8.5 明确"写失败不影响响应"），前端按"本次未留痕"处理。
 */
public class MatchResponse {

    private Long matchId;
    private String engine;
    private String inputType;
    private int topN;
    private BigDecimal minScore;
    private int tookMs;
    private int total;
    private List<MatchItemResponse> items;

    public Long getMatchId() {
        return matchId;
    }

    public void setMatchId(Long matchId) {
        this.matchId = matchId;
    }

    /** 实际执行的实现标识（{@code LOCAL_KEYWORD} / {@code EXTERNAL_EMBEDDING}），答辩与排障看它。 */
    public String getEngine() {
        return engine;
    }

    public void setEngine(String engine) {
        this.engine = engine;
    }

    /** 未限定类型时为 {@code null}（契约 §8.1 允许不传 type）。 */
    public String getInputType() {
        return inputType;
    }

    public void setInputType(String inputType) {
        this.inputType = inputType;
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

    public int getTookMs() {
        return tookMs;
    }

    public void setTookMs(int tookMs) {
        this.tookMs = tookMs;
    }

    public int getTotal() {
        return total;
    }

    public void setTotal(int total) {
        this.total = total;
    }

    public List<MatchItemResponse> getItems() {
        return items;
    }

    public void setItems(List<MatchItemResponse> items) {
        this.items = items;
    }
}
