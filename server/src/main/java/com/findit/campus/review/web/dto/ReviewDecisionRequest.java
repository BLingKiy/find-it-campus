package com.findit.campus.review.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 人工审核结论：理由必填（审计要能回答"为什么"），≤500 字。 */
public class ReviewDecisionRequest {

    @NotBlank(message = "reason 不得为空")
    @Size(max = 500, message = "reason 不得超过 500 字")
    private String reason;

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }
}
