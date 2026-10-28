package com.findit.campus.pair.web.dto;

/**
 * {@code POST /api/pairs/{id}/contact-unlock} 的请求体（契约 §4.15）。
 *
 * <p>{@code action} 省略 = {@code PUBLISH}（兼容 E1 只发 {@code {note}} 的调用）；
 * {@code REVOKE} 时 {@code note} 被忽略并清空。长度校验放在 service：按 Unicode 码点算
 * 「200 字」，emoji 不会被算成两个字符。
 */
public class ContactUnlockRequest {

    private String action;
    private String note;

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }
}
