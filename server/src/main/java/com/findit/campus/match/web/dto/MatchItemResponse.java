package com.findit.campus.match.web.dto;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

import com.findit.campus.common.config.JacksonConfig;
import com.findit.campus.record.domain.RecordStatus;
import com.findit.campus.record.domain.RecordSummary;
import com.findit.campus.record.domain.RecordType;

import com.fasterxml.jackson.annotation.JsonFormat;

/**
 * 匹配结果单项（契约 §8.2）。
 *
 * <p>{@code score} 与 {@code scorePercent} 必须由<b>同一次</b>计算得出（§8.4）：
 * 前端拿 percent 展示、拿 score 排序，若两处各自加工就会出现"显示 83% 但排在 82% 后面"。
 * 因此这里进构造函数的只有引擎给的原始分，两个字段一起算出来。
 *
 * <p>展示字段（type/status/title/categoryName/location/occurredAt/createdAt）来自
 * {@link RecordSummary}（回表结果），与详情接口同值（ADR-008）。
 */
public class MatchItemResponse {

    /** 对外分数口径：0~1 保留 2 位小数（契约 §8.2）。 */
    private static final int SCORE_SCALE = 2;

    private Long recordId;
    private BigDecimal score;
    private Integer scorePercent;
    private String reason;
    private String type;
    private String status;
    private String title;
    private String categoryName;
    private String location;

    @JsonFormat(pattern = JacksonConfig.DATE_TIME_PATTERN)
    private LocalDateTime occurredAt;

    @JsonFormat(pattern = JacksonConfig.DATE_TIME_PATTERN)
    private LocalDateTime createdAt;

    public static MatchItemResponse of(Long recordId, BigDecimal rawScore, String reason, RecordSummary summary) {
        MatchItemResponse item = new MatchItemResponse();
        item.recordId = recordId;
        item.score = rawScore == null ? BigDecimal.ZERO : rawScore.setScale(SCORE_SCALE, RoundingMode.HALF_UP);
        item.scorePercent = item.score.movePointRight(2).setScale(0, RoundingMode.HALF_UP).intValue();
        item.reason = reason;
        item.type = summary.getType() == null ? null : summary.getType().name();
        item.status = summary.getStatus() == null ? null : summary.getStatus().name();
        item.title = summary.getTitle();
        item.categoryName = summary.getCategoryName();
        item.location = summary.getLocation();
        item.occurredAt = summary.getOccurredAt();
        item.createdAt = summary.getCreatedAt();
        return item;
    }

    public Long getRecordId() {
        return recordId;
    }

    public BigDecimal getScore() {
        return score;
    }

    public Integer getScorePercent() {
        return scorePercent;
    }

    public String getReason() {
        return reason;
    }

    public String getType() {
        return type;
    }

    public String getStatus() {
        return status;
    }

    public String getTitle() {
        return title;
    }

    public String getCategoryName() {
        return categoryName;
    }

    public String getLocation() {
        return location;
    }

    public LocalDateTime getOccurredAt() {
        return occurredAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
