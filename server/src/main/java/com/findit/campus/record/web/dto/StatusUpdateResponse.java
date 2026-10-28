package com.findit.campus.record.web.dto;

import java.time.LocalDateTime;
import java.util.List;

import com.findit.campus.common.config.JacksonConfig;
import com.findit.campus.record.domain.Record;
import com.findit.campus.record.domain.RecordStatus;
import com.findit.campus.record.service.RecordStatusPolicy;

import com.fasterxml.jackson.annotation.JsonFormat;

/**
 * {@code PATCH /api/records/{id}/status} 的出参（契约 §4.5）。
 *
 * <p>只回状态相关字段而不是整个详情：调用方只需要知道"现在是什么状态、接下来能做什么"，
 * 回全量会让前端以为"内容也可能被这次调用改了"，从而不敢做局部刷新。
 *
 * <p>{@code completedAt/updatedAt} 取自 UPDATE 后重新读库的行（不是 Java 侧 {@code now()}）：
 * 两个时间由同一条 SQL 写入，回显必须与库里逐字一致，否则证据里的「DB 对照」会对不上。
 */
public class StatusUpdateResponse {

    private Long id;
    private String status;

    @JsonFormat(pattern = JacksonConfig.DATE_TIME_PATTERN)
    private LocalDateTime completedAt;

    @JsonFormat(pattern = JacksonConfig.DATE_TIME_PATTERN)
    private LocalDateTime updatedAt;

    private List<String> allowedStatuses;
    private Boolean hasMatchedPair;
    private Integer matchedPairCount;

    public static StatusUpdateResponse of(Record record, int matchedPairCount) {
        StatusUpdateResponse response = new StatusUpdateResponse();
        response.id = record.getId();
        response.status = record.getStatus() == null ? null : record.getStatus().name();
        response.completedAt = record.getCompletedAt();
        response.updatedAt = record.getUpdatedAt();
        response.hasMatchedPair = matchedPairCount > 0;
        response.matchedPairCount = matchedPairCount;
        response.allowedStatuses = record.getStatus() == null
                ? List.of()
                : RecordStatusPolicy.allowedNext(record.getStatus(), matchedPairCount > 0).stream()
                        .map(RecordStatus::name)
                        .toList();
        return response;
    }

    public Long getId() {
        return id;
    }

    public String getStatus() {
        return status;
    }

    public LocalDateTime getCompletedAt() {
        return completedAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
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
}
