package com.findit.campus.common.config;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.findit.campus.auth.domain.SessionUser;
import com.findit.campus.auth.session.SessionAuth;
import com.findit.campus.match.service.MatchService;
import com.findit.campus.match.web.MatchController;
import com.findit.campus.match.web.dto.MatchRequest;
import com.findit.campus.match.web.dto.MatchResponse;

/**
 * CORS 白名单的回归用例（ZHYYKFX-83）。
 *
 * <p>为什么必须用 MockMvc 而不是 curl：curl 默认不发 {@code Origin}，CORS 处理器直接放行，
 * 这个 bug 在 curl 证据里永远是 200 —— 之前就是这样漏掉的。这里显式带上浏览器会发的
 * {@code Origin: http://127.0.0.1:8088}，让请求真正走进 CORS 分支。
 *
 * <p>覆盖四类边界：
 * <ol>
 *   <li>部署源 {@code 127.0.0.1:8088}：修复前 403 {@code Invalid CORS request}，修复后应放行；</li>
 *   <li>开发源 {@code localhost:5173}：原有行为不能回归；</li>
 *   <li>{@code [::1]:8088}：IPv6 loopback 的端口通配要真的生效；</li>
 *   <li>外部源 {@code evil.example}：白名单没被放宽成 {@code *}，必须仍然 403。</li>
 * </ol>
 * 另外钉住"不带 Origin 时行为不变"（不凭空多出 CORS 响应头）。
 */
@WebMvcTest(controllers = MatchController.class)
@DisplayName("WebConfig CORS：部署源放行 / 开发源不回归 / 外部源仍拒绝")
class WebConfigCorsTest {

    private static final String DEPLOY_ORIGIN = "http://127.0.0.1:8088";
    private static final String DEV_ORIGIN = "http://localhost:5173";
    private static final String IPV6_ORIGIN = "http://[::1]:8088";
    private static final String FOREIGN_ORIGIN = "http://evil.example";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MatchService matchService;

    private MockHttpSession session;

    @BeforeEach
    void setUp() {
        session = new MockHttpSession();
        session.setAttribute(SessionAuth.SESSION_ATTRIBUTE, new SessionUser(1L, "demo01", "演示甲"));

        MatchResponse response = new MatchResponse();
        response.setEngine("LOCAL_KEYWORD");
        response.setTotal(0);
        response.setItems(List.of());
        when(matchService.match(any(MatchRequest.class), eq(1L))).thenReturn(response);
    }

    @Test
    @DisplayName("部署源 127.0.0.1:8088 的 POST 不是 403 Invalid CORS request")
    void deployOriginIsAllowed() throws Exception {
        mockMvc.perform(post("/api/match")
                        .session(session)
                        .header(HttpHeaders.ORIGIN, DEPLOY_ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"丢失一张校园卡\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, DEPLOY_ORIGIN))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"))
                .andExpect(content().string(not(containsString("Invalid CORS request"))));
    }

    @Test
    @DisplayName("开发源 localhost:5173 行为不变")
    void devOriginStillAllowed() throws Exception {
        mockMvc.perform(post("/api/match")
                        .session(session)
                        .header(HttpHeaders.ORIGIN, DEV_ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"丢失一张校园卡\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, DEV_ORIGIN));
    }

    @Test
    @DisplayName("IPv6 loopback http://[::1]:8088 也被端口通配覆盖")
    void ipv6LoopbackIsAllowed() throws Exception {
        mockMvc.perform(post("/api/match")
                        .session(session)
                        .header(HttpHeaders.ORIGIN, IPV6_ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"丢失一张校园卡\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, IPV6_ORIGIN));
    }

    @Test
    @DisplayName("预检 OPTIONS 也必须放行部署源")
    void preflightFromDeployOriginIsAllowed() throws Exception {
        mockMvc.perform(options("/api/match")
                        .header(HttpHeaders.ORIGIN, DEPLOY_ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, DEPLOY_ORIGIN))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, containsString("POST")))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"));
    }

    @Test
    @DisplayName("外部源仍然 403，白名单没有被放宽成 *")
    void foreignOriginIsRejected() throws Exception {
        mockMvc.perform(post("/api/match")
                        .session(session)
                        .header(HttpHeaders.ORIGIN, FOREIGN_ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"丢失一张校园卡\"}"))
                .andExpect(status().isForbidden())
                .andExpect(content().string(containsString("Invalid CORS request")));
    }

    @Test
    @DisplayName("不带 Origin 时行为不变：200 且不返回 CORS 响应头")
    void noOriginBehavesAsBefore() throws Exception {
        mockMvc.perform(post("/api/match")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"丢失一张校园卡\"}"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }
}
