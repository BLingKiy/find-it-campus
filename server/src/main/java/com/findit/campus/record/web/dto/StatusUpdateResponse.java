package com.findit.campus.record.web.dto;

import java.util.List;

import com.findit.campus.record.domain.RecordStatus;
import com.findit.campus.record.service.RecordStatusPolicy;

/**
 * {@code PATCH /api/records/{id}/status} 的出参（契约 §6.1）。
 *
 * <p>只回三个字段而不是回整个详情：状态流转的调用方只需要知道"现在是什么状态、接下来能做什么"，
 * 回全量会让前端以为"内容也可能被这次调用改了"，从而不敢做局部刷新。
 */
public class StatusUpdateResponse {

    private Long id;
    private String status;
    private List<String> allowedStatuses;

    public static StatusUpdateResponse of(Long id, RecordStatus status) {
        StatusUpdateResponse response = new StatusUpdateResponse();
        response.id = id;
        response.status = status == null ? null : status.name();
        response.allowedStatuses = status == null
                ? List.of()
                : RecordStatusPolicy.allowedNext(status).stream().map(RecordStatus::name).toList();
        return response;
    }

    public Long getId() {
        return id;
    }

    public String getStatus() {
        return status;
    }

    public List<String> getAllowedStatuses() {
        return allowedStatuses;
    }
}
