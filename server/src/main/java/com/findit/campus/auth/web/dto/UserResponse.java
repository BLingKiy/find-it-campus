package com.findit.campus.auth.web.dto;

import com.findit.campus.auth.domain.SessionUser;
import com.findit.campus.auth.domain.User;

/**
 * 登录成功 / 当前用户的出参（契约 §4.9、§4.11）：{@code {id, studentNo, displayName}}。
 *
 * <p><b>刻意只有三个字段</b>：{@code passwordHash} 永远不出现在任何响应里；
 * {@code is_active} 也不给 —— 前端不需要，多了反而会被误当成权限位。
 * 出参与契约同名字段一一对应，B3 的 TS 类型直接照抄即可。
 */
public class UserResponse {

    private Long id;
    private String studentNo;
    private String displayName;

    public static UserResponse from(User user) {
        UserResponse response = new UserResponse();
        response.id = user.getId();
        response.studentNo = user.getStudentNo();
        response.displayName = user.getDisplayName();
        return response;
    }

    public static UserResponse from(SessionUser user) {
        UserResponse response = new UserResponse();
        response.id = user.id();
        response.studentNo = user.studentNo();
        response.displayName = user.displayName();
        return response;
    }

    public Long getId() {
        return id;
    }

    public String getStudentNo() {
        return studentNo;
    }

    public String getDisplayName() {
        return displayName;
    }
}
