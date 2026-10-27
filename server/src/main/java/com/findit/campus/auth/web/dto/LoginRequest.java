package com.findit.campus.auth.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 登录请求体（契约 §4.9）。
 *
 * <p>为什么用可变 Bean 而不是 record：{@code studentNo} 需要在 setter 里 {@code strip()}
 * —— 用户从表格里复制学号经常带首尾空白，那不属于"输入错误"，不该逼用户自己找；
 * 而 {@code password} 绝不能 strip：首尾空格是口令的一部分，悄悄改写口令等于制造登录失败。
 *
 * <p>长度口径按契约（学号 1–32、口令 1–64）：缺失与超长都走 10001 + fieldErrors，
 * 不是 401 —— 参数形状不对是调用方的问题，与"凭证错"不是一类。
 */
public class LoginRequest {

    @NotBlank(message = "学号不得为空")
    @Size(max = 32, message = "学号长度需在 1 到 32 之间")
    private String studentNo;

    @NotBlank(message = "口令不得为空")
    @Size(max = 64, message = "口令长度需在 1 到 64 之间")
    private String password;

    public String getStudentNo() {
        return studentNo;
    }

    public void setStudentNo(String studentNo) {
        this.studentNo = studentNo == null ? null : studentNo.strip();
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
