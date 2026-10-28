package com.findit.campus.record.web.dto;

import java.time.LocalDateTime;
import java.util.List;

import com.findit.campus.common.config.JacksonConfig;
import com.findit.campus.record.domain.MyPairCounts;
import com.findit.campus.record.domain.MyRecordRow;
import com.findit.campus.record.domain.RecordStatus;

import com.fasterxml.jackson.annotation.JsonFormat;

/**
 * 「我的启事」列表项（契约 §4.12）：<b>所有者视角</b>，因此 contact/publisherName/location/title 均为原文，
 * 不经过 {@code MaskingPolicy}；这是本人数据回显，不是跨用户泄露。
 *
 * <p>{@code detailVisible}：{@code PENDING=true}，{@code COMPLETED/CLOSED=false}。
 * COMPLETED 按 A9 字面"所有者详情也 404"，前端据此不渲染详情入口；本类与详情出口共用
 * {@code RecordVisibilityPolicy} 的口径，不会出现"列表能点、点进去 404"。
 *
 * <p>{@code pairs} 是每条记录自己的 pair 卡片（契约 §4.12 / §5.1），顺序由服务端定
 * （{@code score} 降序、同分 {@code pairId} 升序），前端不再排。与详情不同，<b>本接口的所有条目都是
 * 所有者视角</b>，所以 {@code pairs} 恒存在：没有达阈值的有效 pair 时给 {@code []}（不是省略）。
 */
public class MyRecordListItemResponse {

    private Long id;
    private String type;
    private String status;
    private String title;
    private Long categoryId;
    private String categoryName;
    private String location;

    @JsonFormat(pattern = JacksonConfig.DATE_TIME_PATTERN)
    private LocalDateTime occurredAt;

    @JsonFormat(pattern = JacksonConfig.DATE_TIME_PATTERN)
    private LocalDateTime completedAt;

    private String contact;
    private String publisherName;
    private String pairState;
    /**
     * 降级标志位（D1）：{@code pairState=DEGRADED} 的布尔投影。
     *
     * <p>为什么在已有 {@code pairState} 之外再加一个布尔：前端要显示
     * 「智能匹配暂不可用，已用关键词匹配」，判据是"有没有降级"，而 {@code pairState}
     * 还混着 PENDING/SCORING/READY 三种非降级值。让前端写 {@code pairState === 'DEGRADED'}
     * 也能做，但那样"降级"这个业务事实就被编码进了字符串比较，改一个枚举值就静默失效。
     * 服务端给一个明确的布尔，代价是一个字段，换来的是前端不必知道枚举语义。
     */
    private Boolean pairDegraded;
    private Integer pairCount;
    private Integer relatedCount;
    private Integer lowConfidenceCount;
    private Boolean hasMatchedPair;
    private Integer matchedPairCount;
    private Boolean detailVisible;

    /** 恒存在（所有者视角）：无有效卡片时 {@code []}，不是 {@code null}。 */
    private List<PairCard> pairs;

    @JsonFormat(pattern = JacksonConfig.DATE_TIME_PATTERN)
    private LocalDateTime createdAt;

    @JsonFormat(pattern = JacksonConfig.DATE_TIME_PATTERN)
    private LocalDateTime updatedAt;

    public static MyRecordListItemResponse of(MyRecordRow row, MyPairCounts counts, List<PairCard> pairs) {
        MyPairCounts effective = counts == null ? MyPairCounts.EMPTY : counts;
        MyRecordListItemResponse response = new MyRecordListItemResponse();
        response.id = row.getId();
        response.type = row.getType() == null ? null : row.getType().name();
        response.status = row.getStatus() == null ? null : row.getStatus().name();
        response.title = row.getTitle();
        response.categoryId = row.getCategoryId();
        response.categoryName = row.getCategoryName();
        response.location = row.getLocation();
        response.occurredAt = row.getOccurredAt();
        response.completedAt = row.getCompletedAt();
        response.contact = row.getContact();
        response.publisherName = row.getPublisherName();
        response.pairState = row.getPairState();
        response.pairDegraded = "DEGRADED".equals(row.getPairState());
        response.pairCount = effective.total();
        response.relatedCount = effective.related();
        response.lowConfidenceCount = effective.lowConfidence();
        response.hasMatchedPair = effective.matched() > 0;
        response.matchedPairCount = effective.matched();
        response.detailVisible = row.getStatus() == RecordStatus.PENDING;
        response.pairs = pairs == null ? List.of() : pairs;
        response.createdAt = row.getCreatedAt();
        response.updatedAt = row.getUpdatedAt();
        return response;
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

    public String getPairState() {
        return pairState;
    }

    public Boolean getPairDegraded() {
        return pairDegraded;
    }

    public Integer getPairCount() {
        return pairCount;
    }

    public Integer getRelatedCount() {
        return relatedCount;
    }

    public Integer getLowConfidenceCount() {
        return lowConfidenceCount;
    }

    public Boolean getHasMatchedPair() {
        return hasMatchedPair;
    }

    public Integer getMatchedPairCount() {
        return matchedPairCount;
    }

    public Boolean getDetailVisible() {
        return detailVisible;
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
