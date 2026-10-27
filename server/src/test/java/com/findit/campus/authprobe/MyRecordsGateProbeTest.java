package com.findit.campus.authprobe;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import com.findit.campus.auth.domain.SessionUser;
import com.findit.campus.auth.session.SessionAuth;

/**
 * B2 验收项「无 cookie 访问 {@code /api/my/records} → 401 + 统一错误体」的可执行版本。
 *
 * <p>用一个 test-only 探针冒充 C1 的路由（见 {@link MyRecordsProbeController}）：验的是
 * "拦截规则 + 401 出口"，不是"我的启事"的业务内容。C1 落地后把这两条断言改打真接口即可。
 */
@WebMvcTest(controllers = MyRecordsProbeController.class)
@DisplayName("/api/my/records 登录门禁（探针）")
class MyRecordsGateProbeTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("无会话 → 401 + 10006 + 请先登录（不是 404、不是 500、不是空列表）")
    void anonymousIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/my/records"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(10006))
                .andExpect(jsonPath("$.message").value("请先登录"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    @DisplayName("有会话 → 200（门禁放行，确实会走到业务 handler）")
    void signedInPasses() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionAuth.SESSION_ATTRIBUTE, new SessionUser(1L, "demo01", "演示甲"));

        mockMvc.perform(get("/api/my/records").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value("probe"));
    }
}
