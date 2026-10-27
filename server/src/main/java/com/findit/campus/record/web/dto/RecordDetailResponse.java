package com.findit.campus.record.web.dto;

import java.time.LocalDateTime;
import java.util.List;

import com.findit.campus.common.config.JacksonConfig;
import com.findit.campus.common.masking.MaskingPolicy;
import com.findit.campus.record.domain.Record;
import com.findit.campus.record.domain.RecordStatus;
import com.findit.campus.record.service.RecordStatusPolicy;

import com.fasterxml.jackson.annotation.JsonFormat;

/**
 * 详情出参（契约 §4.2）。也是 {@code POST}/{@code PUT} 的返回体（与详情同构）。
 *
 * <p><b>身份维度在这里落地，而不是在 controller</b>：service 判定"是否所有者 / 是否合格对端"，
 * 把 {@code ownerView} 传进来；本类只负责"按视角取原文或 {@link MaskingPolicy} 的结果"。
 * 两个工厂方法的分工：
 * <ul>
 *   <li>{@link #from(Record)}：<b>遮蔽视角</b>（fail-closed 默认）。匿名/非所有者/合格对端都用它，
 *       任何新调用点即使忘了判身份，最坏也是"多打了码"，不会把号码漏出去。</li>
 *   <li>{@link #ownerView(Record, int, int)}：本人明文视角，带 pair 计数与真实的 {@code allowedStatuses}。</li>
 * </ul>
 *
 * <p>{@code allowedStatuses} 由 {@link RecordStatusPolicy} 给出而不是前端自己推：
 * 状态机放后端，"改流转规则要前后端一起改"这件事就不存在了。终态返回空数组。
 *
 * <p>{@code pairs}（PairCard[]）不在 C1 交付范围：它依赖 E1 的 pair 四态/授权/取号语义，
 * C1 只给 {@code hasMatchedPair}/{@code matchedPairCount} 这些"决定按钮能不能点"的计数。
 * 提前拼一个半成品卡片，会让 E1 一落地就得改返回结构，属于做了会后悔的事。
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

    @JsonFormat(pattern = JacksonConfig.DATE_TIME_PATTERN)
    private LocalDateTime createdAt;

    @JsonFormat(pattern = JacksonConfig.DATE_TIME_PATTERN)
    private LocalDateTime updatedAt;

    /** 遮蔽视角（匿名 / 登录非所有者 / COMPLETED 的合格对端）。 */
    public static RecordDetailResponse from(Record record) {
        return build(record, false, 0, 0);
    }

    /** 所有者视角：明文 + 计数 + 真实 allowedStatuses。 */
    public static RecordDetailResponse ownerView(Record record, int matchedPairCount, int lowConfidenceCount) {
        return build(record, true, matchedPairCount, lowConfidenceCount);
    }

    private static RecordDetailResponse build(Record record, boolean ownerView, int matchedPairCount,
                                              int lowConfidenceCount) {
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
        if (ownerView) {
            response.title = record.getTitle();
            response.description = record.getDescription();
            response.location = record.getLocation();
            response.completedAt = record.getCompletedAt();
            response.contact = record.getContact();
            response.publisherName = record.getPublisherName();
            response.pairState = record.getPairState();
            response.hasMatchedPair = matchedPairCount > 0;
            response.matchedPairCount = matchedPairCount;
            response.lowConfidenceCount = lowConfidenceCount;
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

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
