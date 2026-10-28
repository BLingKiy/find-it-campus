package com.findit.campus.auth.domain;

/**
 * {@code t_user} 的一行（登录身份）。
 *
 * <p>对照契约 §4.2.1：{@code student_no} 是登录名且唯一，{@code password_hash} 只存 bcrypt，
 * <b>没有 phone / email / 验证码列</b> —— 手机号只在记录里做格式校验，不落这张表。
 *
 * <p>注意：这是内部实体，<b>不是出参</b>。出参一律走 {@code UserResponse}（只含 id / studentNo / displayName），
 * 所以 {@code passwordHash} 没有机会被序列化到响应里；把本类直接返给前端等于把口令哈希交给浏览器，
 * 这是这条分包边界存在的理由之一。
 */
public class User {

    private Long id;
    private String studentNo;
    private String passwordHash;
    private String displayName;
    private Boolean active;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getStudentNo() {
        return studentNo;
    }

    public void setStudentNo(String studentNo) {
        this.studentNo = studentNo;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public Boolean getActive() {
        return active;
    }

    public void setActive(Boolean active) {
        this.active = active;
    }
}
