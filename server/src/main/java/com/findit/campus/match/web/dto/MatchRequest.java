package com.findit.campus.match.web.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

/**
 * {@code POST /api/match} 的请求体（契约 §8.1）。
 *
 * <p><b>刻意不给 {@code text} 加校验注解</b>：契约 §8.3 规定 text 非法回 {@code 30001}
 * 而不是通用的 {@code 10001}，而 Bean Validation 只能产出后者。text 的校验放在
 * {@code MatchService} 里抛 {@code BizException(30001)}，并同样带 fieldErrors。
 *
 * <p>其余字段（type/topN/minScore）越界按契约回 {@code 10001}，因此正常用注解校验 ——
 * 同一个请求里两种错误码并存是契约明确要求的口径，不是实现偷懒。
 */
public class MatchRequest {

    public static final int DEFAULT_TOP_N = 5;
    public static final int MAX_TOP_N = 20;
    public static final String DEFAULT_MIN_SCORE = "0.3";

    private String text;

    /** 含义是"我要找的记录类型"（失主想找招领帖 ⇒ {@code FOUND}）；不传 = 不限类型。 */
    @Pattern(regexp = "LOST|FOUND", message = "type 取值非法：LOST / FOUND")
    private String type;

    private Long categoryId;

    @Min(value = 1, message = "topN 需大于等于 1")
    @Max(value = MAX_TOP_N, message = "topN 不得超过 20")
    private Integer topN = DEFAULT_TOP_N;

    @DecimalMin(value = "0", message = "minScore 需在 0 到 1 之间")
    @DecimalMax(value = "1", message = "minScore 需在 0 到 1 之间")
    private BigDecimal minScore = new BigDecimal(DEFAULT_MIN_SCORE);

    /** 从"某条记录详情页"发起匹配时排除自身。 */
    private Long excludeRecordId;

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text == null ? null : text.strip();
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type == null || type.isBlank() ? null : type.strip();
    }

    public Long getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(Long categoryId) {
        this.categoryId = categoryId;
    }

    public Integer getTopN() {
        return topN;
    }

    public void setTopN(Integer topN) {
        this.topN = topN;
    }

    public BigDecimal getMinScore() {
        return minScore;
    }

    public void setMinScore(BigDecimal minScore) {
        this.minScore = minScore;
    }

    public Long getExcludeRecordId() {
        return excludeRecordId;
    }

    public void setExcludeRecordId(Long excludeRecordId) {
        this.excludeRecordId = excludeRecordId;
    }

    public int topNOrDefault() {
        return topN == null ? DEFAULT_TOP_N : topN;
    }

    public BigDecimal minScoreOrDefault() {
        return minScore == null ? new BigDecimal(DEFAULT_MIN_SCORE) : minScore;
    }
}
