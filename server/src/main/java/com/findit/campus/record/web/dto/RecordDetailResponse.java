package com.findit.campus.record.web.dto;

import java.time.LocalDateTime;
import java.util.List;

import com.findit.campus.common.config.JacksonConfig;
import com.findit.campus.common.masking.MaskingPolicy;
import com.findit.campus.record.domain.Record;
import com.findit.campus.record.domain.RecordStatus;
import com.findit.campus.record.service.RecordStatusPolicy;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 详情出参（契约 §4.2）。也是 {@code POST}/{@code PUT} 的返回体（与详情同构）。
 *
 * <p><b>身份维度在这里落地，而不是在 controller</b>：service 判定"是否所有者 / 是否合格对端"，
 * 把 {@code ownerView} 传进来；本类只负责"按视角取原文或 {@link MaskingPolicy} 的结果"。
 * 两个工厂方法的分工：
 * <ul>
 *   <li>{@link #from(Record)}：<b>遮蔽视角</b>（fail-closed 默认）。匿名/非所有者/合格对端都用它，
 *       任何新调用点即使忘了判身份，最坏也是"多打了码"，不会把号码漏出去。</li>
 *   <li>{@link #ownerView(Record, int, int, java.util.List)}：本人明文视角，带 pair 计数与真实的
 *       {@code allowedStatuses}。</li>
 * </ul>
 *
 * <p>{@code allowedStatuses} 由 {@link RecordStatusPolicy} 给出而不是前端自己推：
 * 状态机放后端，"改流转规则要前后端一起改"这件事就不存在了。终态返回空数组。
 *
 * <p>{@code pairs}（PairCard[]）只属于<b>所有者 PENDING 详情</b>（契约 §4.2 / §5.2）：
 * 非所有者/匿名、COMPLETED 合格对端的遮蔽视图<b>省略该字段</b>（不是空数组）——
 * 用 {@link JsonInclude.Include#NON_NULL} 表达"没有这个键"，而不是给前端一个空数组让它以为
 * "查过了、确实没有配对"。字段为空数组时仍然序列化（发布成功响应要求 {@code pairs=[]}）。
 */
public class RecordDetailResponse {

    private Long id;
    private String type;
    private String status;
    private String title;
    private String description;
    private Long categoryId;
    private String categoryName;
    private String location;

    @JsonFormat(pattern = JacksonConfig.DATE_TIME_PATTERN)
    private LocalDateTime occurredAt;

    /** 非所有者不可见（含合格对端）：完成时间是所有者数据。 */
    @JsonFormat(pattern = JacksonConfig.DATE_TIME_PATTERN)
    private LocalDateTime completedAt;

    /** 非所有者一律 {@code null}：不做 138****0001 这类部分掩码（仍泄露号段与后四位）。 */
    private String contact;
    private String publisherName;
    private List<String> allowedStatuses;
    private Boolean hasMatchedPair;
    private Integer matchedPairCount;
    private Boolean isMine;
    private String pairState;
    private Integer lowConfidenceCount;

    // ---- 发布闸门（ZHYYKFX-85）：审核态对外只出"结论 + 理由"，不出原文 ----
    private String reviewState;
    private Boolean reviewManualRequired;
    private String reviewReason;

    @JsonFormat(pattern = JacksonConfig.DATE_TIME_PATTERN)
    private LocalDateTime reviewedAt;

    private String summary;

    /**
     * 原文三通道：<b>仅本人详情</b>返回；匿名/非所有者整个键被省略（NON_NULL）。
     * 每次本人详情读取都会写一条 {@code RAW_VIEW} 审计（见 RecordService）。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String rawTitle;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String rawDescription;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String rawLocation;

    /**
     * 所有者视角的 pair 卡片（契约 §4.2 / §5.1），按匹配度降序。
     *
     * <p>{@code null} = 这个视角不返回该字段（非所有者 / 匿名 / COMPLETED 合格对端），
     * 序列化时整个键被省略；空列表 = 所有者视角、确实没有达阈值的有效 pair。
     * 两者语义不同，不能合并成 {@code []}。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private List<PairCard> pairs;

    @JsonFormat(pattern = JacksonConfig.DATE_TIME_PATTERN)
    private LocalDateTime createdAt;

    @JsonFormat(pattern = JacksonConfig.DATE_TIME_PATTERN)
    private LocalDateTime updatedAt;

    /** 遮蔽视角（匿名 / 登录非所有者 / COMPLETED 的合格对端）。{@code pairs} 字段整体省略。 */
    public static RecordDetailResponse from(Record record) {
        return build(record, false, 0, 0, null);
    }

    /** 所有者视角：明文 + 计数 + 真实 allowedStatuses + pair 卡片。 */
    public static RecordDetailResponse ownerView(Record record, int matchedPairCount, int lowConfidenceCount,
                                                 List<PairCard> pairs) {
        return build(record, true, matchedPairCount, lowConfidenceCount, pairs);
    }

    private static RecordDetailResponse build(Record record, boolean ownerView, int matchedPairCount,
                                              int lowConfidenceCount, List<PairCard> pairs) {
        RecordDetailResponse response = new RecordDetailResponse();
        response.id = record.getId();
        response.type = record.getType() == null ? null : record.getType().name();
        response.status = record.getStatus() == null ? null : record.getStatus().name();
        response.categoryId = record.getCategoryId();
        response.categoryName = record.getCategoryName();
        response.occurredAt = record.getOccurredAt();
        response.createdAt = record.getCreatedAt();
        response.updatedAt = record.getUpdatedAt();
        response.isMine = ownerView;
        response.reviewState = record.getReviewState() == null ? null : record.getReviewState().name();
        response.summary = record.getSummary();
        if (ownerView) {
            response.title = record.getTitle();
            response.description = record.getDescription();
            response.location = record.getLocation();
            response.completedAt = record.getCompletedAt();
            response.contact = record.getContact();
            response.publisherName = record.getPublisherName();
            response.pairState = record.getPairState();
            response.reviewManualRequired = Boolean.TRUE.equals(record.getReviewManualRequired());
            response.reviewReason = record.getReviewReason();
            response.reviewedAt = record.getReviewedAt();
            // 只有真正按 AI 通道发布的行才有 raw_*；mode=off 不返回这三个键，保持 v2.0 形状
            if (record.getRawTitle() != null) {
                response.rawTitle = record.rawTitleOrDefault();
                response.rawDescription = record.rawDescriptionOrDefault();
                response.rawLocation = record.rawLocationOrDefault();
            }
            response.hasMatchedPair = matchedPairCount > 0;
            response.matchedPairCount = matchedPairCount;
            response.lowConfidenceCount = lowConfidenceCount;
            response.pairs = pairs == null ? List.of() : pairs;
            response.allowedStatuses = allowedStatusesOf(record.getStatus(), matchedPairCount > 0);
        } else {
            response.title = MaskingPolicy.maskDescription(record.getTitle());
            response.description = MaskingPolicy.maskDescription(record.getDescription());
            response.location = MaskingPolicy.maskLocation(record.getLocation());
            response.completedAt = null;
            response.contact = null;
            response.publisherName = MaskingPolicy.maskPublisherName(record.getPublisherName());
            response.pairState = null;
            response.hasMatchedPair = false;
            response.matchedPairCount = 0;
            response.lowConfidenceCount = 0;
            // null（不是空数组）：字段被 NON_NULL 省略，见类注释
            response.pairs = null;
            response.allowedStatuses = List.of();
        }
        return response;
    }

    /** 终态返回空数组（不是 null），前端可以直接 {@code .length} 判断。 */
    private static List<String> allowedStatusesOf(RecordStatus status, boolean hasValidMatchedPair) {
        if (status == null) {
            return List.of();
        }
        return RecordStatusPolicy.allowedNext(status, hasValidMatchedPair).stream()
                .map(RecordStatus::name)
                .toList();
    }

    public Long getId() {
        return id;
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

    public String getDescription() {
        return description;
    }

    public Long getCategoryId() {
        return categoryId;
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

    public LocalDateTime getCompletedAt() {
        return completedAt;
    }

    public String getContact() {
        return contact;
    }

    public String getPublisherName() {
        return publisherName;
    }

    public List<String> getAllowedStatuses() {
        return allowedStatuses;
    }

    public Boolean getHasMatchedPair() {
        return hasMatchedPair;
    }

    public Integer getMatchedPairCount() {
        return matchedPairCount;
    }

    public Boolean getIsMine() {
        return isMine;
    }

    public String getPairState() {
        return pairState;
    }

    public Integer getLowConfidenceCount() {
        return lowConfidenceCount;
    }

    public String getReviewState() {
        return reviewState;
    }

    public Boolean getReviewManualRequired() {
        return reviewManualRequired;
    }

    public String getReviewReason() {
        return reviewReason;
    }

    public LocalDateTime getReviewedAt() {
        return reviewedAt;
    }

    public String getSummary() {
        return summary;
    }

    public String getRawTitle() {
        return rawTitle;
    }

    public String getRawDescription() {
        return rawDescription;
    }

    public String getRawLocation() {
        return rawLocation;
    }

    public List<PairCard> getPairs() {
        return pairs;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
