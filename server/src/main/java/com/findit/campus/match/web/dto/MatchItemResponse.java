package com.findit.campus.match.web.dto;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

import com.findit.campus.common.config.JacksonConfig;
import com.findit.campus.common.masking.MaskingPolicy;
import com.findit.campus.record.domain.RecordStatus;
import com.findit.campus.record.domain.RecordSummary;

import com.fasterxml.jackson.annotation.JsonFormat;

/**
 * 匹配结果单项（契约 §8.2）。
 *
 * <p>{@code score} 与 {@code scorePercent} 必须由<b>同一次</b>计算得出：前端拿 percent 展示、
 * 拿 score 排序，若两处各自加工就会出现"显示 83% 但排在 82% 后面"。因此进构造函数的只有引擎给的原始分。
 *
 * <p>展示字段（title/location/…）来自回表结果 {@link RecordSummary}，与详情接口走<b>同一个</b>
 * {@link MaskingPolicy}：匿名与登录非所有者看到的是打码后的标题/截断后的地点；
 * 只有"候选恰好是本人记录"时才返回原文（身份维度与列表/详情一致）。{@code reason} 是引擎自由文本，
 * 统一过打码器（防御性：引擎可能回显输入片段）。
 *
 * <p>v2.1 新增两字段：
 * <ul>
 *   <li>{@code recordStatus ∈ {PENDING, COMPLETED}}：候选池含保留期内的 COMPLETED</li>
 *   <li>{@code detailAccess ∈ {OPEN, RESTRICTED}}：COMPLETED 恒 RESTRICTED —— 命中不等于有详情权限，
 *       点进去仍由详情门槛决定（前端不得把匹配结果当授权）</li>
 * </ul>
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
    private String recordStatus;
    private String detailAccess;
    private String title;
    private String categoryName;
    private String location;

    @JsonFormat(pattern = JacksonConfig.DATE_TIME_PATTERN)
    private LocalDateTime occurredAt;

    @JsonFormat(pattern = JacksonConfig.DATE_TIME_PATTERN)
    private LocalDateTime createdAt;

    /** fail-closed 默认：不传身份就按遮蔽视角装配。 */
    public static MatchItemResponse of(Long recordId, BigDecimal rawScore, String reason, RecordSummary summary) {
        return of(recordId, rawScore, reason, summary, false);
    }

    /**
     * @param ownerView 候选是否为查看者本人的记录（true = 原文；false = 遮蔽）
     */
    public static MatchItemResponse of(Long recordId, BigDecimal rawScore, String reason, RecordSummary summary,
                                       boolean ownerView) {
        MatchItemResponse item = new MatchItemResponse();
        item.recordId = recordId;
        item.score = rawScore == null ? BigDecimal.ZERO : rawScore.setScale(SCORE_SCALE, RoundingMode.HALF_UP);
        item.scorePercent = item.score.movePointRight(2).setScale(0, RoundingMode.HALF_UP).intValue();
        item.reason = MaskingPolicy.maskDescription(reason);
        item.type = summary.getType() == null ? null : summary.getType().name();
        item.status = summary.getStatus() == null ? null : summary.getStatus().name();
        item.recordStatus = item.status;
        item.detailAccess = summary.getStatus() == RecordStatus.COMPLETED ? "RESTRICTED" : "OPEN";
        item.title = ownerView ? summary.getTitle() : MaskingPolicy.maskDescription(summary.getTitle());
        item.categoryName = summary.getCategoryName();
        item.location = ownerView ? summary.getLocation() : MaskingPolicy.maskLocation(summary.getLocation());
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

    public String getRecordStatus() {
        return recordStatus;
    }

    public String getDetailAccess() {
        return detailAccess;
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
