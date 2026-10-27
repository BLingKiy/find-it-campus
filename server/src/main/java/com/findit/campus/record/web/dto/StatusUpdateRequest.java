package com.findit.campus.record.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * {@code PATCH /api/records/{id}/status} 的请求体（契约 §4.5）。
 *
 * <p>用 {@code String} 而不是 {@code RecordStatus} 枚举是有意的：枚举反序列化失败会变成
 * "请求体不可解析"（{@code 10001} 但拿不到字段名），前端无法把错误落到"状态"这个控件上；
 * {@code @Pattern} 能给出 {@code field=status} 的 fieldErrors。
 *
 * <p><b>目标值只允许 {@code COMPLETED/CLOSED}</b>：{@code PENDING} 不能作为目标（回到进行中不是合法动作），
 * {@code CLAIMED} 已作废。收到这些值一律 400/10001，而不是 409 —— 它们不是"状态机不允许的流转"，
 * 而是"这个接口根本不接受的值"，两者前端处理方式不同。
 */
public class StatusUpdateRequest {

    @NotBlank(message = "status 不得为空")
    @Pattern(regexp = "COMPLETED|CLOSED", message = "status 取值非法：COMPLETED / CLOSED")
    private String status;

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status == null ? null : status.strip();
    }
}
