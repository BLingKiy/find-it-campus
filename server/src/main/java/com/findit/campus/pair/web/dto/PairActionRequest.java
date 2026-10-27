package com.findit.campus.pair.web.dto;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonAnySetter;

/**
 * {@code PATCH /api/pairs/{id}} 的请求体（契约 §4.13）：只接 {@code action}。
 *
 * <p><b>为什么用 {@link JsonAnySetter} 接住未知字段</b>：契约明确「不能直传 MATCHED」。
 * 如果 DTO 只声明 {@code action} 并让 Jackson 忽略未知字段，客户端发
 * {@code {"action":"CONFIRM","status":"MATCHED"}} 会被静默接受 —— 看起来像是服务端接受了
 * 客户端指定状态，实际上只是被忽略。这里把未知字段全部收集起来并返回 400/10001，
 * 让「客户端试图直传状态」在接口层就失败。
 */
public class PairActionRequest {

    private String action;
    private final Map<String, Object> unknownFields = new LinkedHashMap<>();

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    @JsonAnySetter
    public void putUnknown(String name, Object value) {
        unknownFields.put(name, value);
    }

    public String firstUnknownField() {
        return unknownFields.isEmpty() ? null : unknownFields.keySet().iterator().next();
    }
}
