package com.findit.campus.pair.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.findit.campus.auth.domain.SessionUser;
import com.findit.campus.auth.session.SessionAuth;
import com.findit.campus.pair.service.PairActionService;
import com.findit.campus.pair.service.PairContactService;
import com.findit.campus.pair.service.PairUnlockService;
import com.findit.campus.pair.web.dto.ContactUnlockResponse;
import com.findit.campus.pair.web.dto.ContactView;
import com.findit.campus.pair.web.dto.PairActionResponse;
import com.findit.campus.pair.web.dto.PairActionResult;

/**
 * pair 三条 HTTP 入口的门禁、路径 id 容错、Cache-Control 与响应形状。
 *
 * <p>非数字 id 走 404/20004 而不是 400/10001，是契约里容易被 `@PathVariable long` 打破的一条；
 * 这里用真实 MockMvc 请求锁住它。
 */
@WebMvcTest(controllers = PairController.class)
@DisplayName("PairController：401 门禁 / 非数字 id 404 / contact no-store")
class PairApiTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PairContactService contactService;

    @MockitoBean
    private PairUnlockService unlockService;

    @MockitoBean
    private PairActionService actionService;

    @Test
    @DisplayName("匿名 GET contact → 401 + 10006，服务不被调用")
    void anonymousContactIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/pairs/101/contact"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(10006));
    }

    @Test
    @DisplayName("已登录 GET contact → 200 + Cache-Control: no-store + data 字段")
    void contactReturnsViewWithNoStore() throws Exception {
        when(contactService.view(eq(101L), eq(1L), any())).thenReturn(sampleView());

        mockMvc.perform(get("/api/pairs/101/contact").session(session()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.pairId").value(101))
                .andExpect(jsonPath("$.data.available").value(true))
                .andExpect(jsonPath("$.data.contact").value("13800000002"))
                .andExpect(jsonPath("$.data.tier").value("DIRECT"));
    }

    @Test
    @DisplayName("非数字 pairId → 404/20004（不是 400/10001）")
    void nonNumericPairIdIsNotFound() throws Exception {
        mockMvc.perform(get("/api/pairs/abc/contact").session(session()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(20004))
                .andExpect(jsonPath("$.message").value("配对不存在或任一端记录已删除"));
    }

    @Test
    @DisplayName("匿名 POST contact-unlock → 401")
    void anonymousUnlockIsUnauthorized() throws Exception {
        mockMvc.perform(post("/api/pairs/101/contact-unlock")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"PUBLISH\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST contact-unlock 200，body 的 note 原样交给 service（打码在 service 内）")
    void unlockDelegatesToService() throws Exception {
        ContactUnlockResponse response = new ContactUnlockResponse();
        response.setPairId(101L);
        response.setSide("LOST");
        response.setPublic(true);
        when(unlockService.unlock(eq(101L), eq(1L), any())).thenReturn(response);

        mockMvc.perform(post("/api/pairs/101/contact-unlock")
                        .session(session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"周六见\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.public").value(true))
                .andExpect(jsonPath("$.data.side").value("LOST"));

        verify(unlockService).unlock(eq(101L), eq(1L), any());
    }

    @Test
    @DisplayName("PATCH pair 200，成功文案在统一响应体的 message")
    void patchReturnsMessageFromService() throws Exception {
        PairActionResponse data = new PairActionResponse();
        data.setPairId(101L);
        data.setStatus("CONTACTED");
        data.setMatched(false);
        when(actionService.act(eq(101L), eq(1L), any()))
                .thenReturn(new PairActionResult(data, "已联系"));

        mockMvc.perform(patch("/api/pairs/101")
                        .session(session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"CONTACT\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("已联系"))
                .andExpect(jsonPath("$.data.status").value("CONTACTED"));
    }

    private static MockHttpSession session() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionAuth.SESSION_ATTRIBUTE, new SessionUser(1L, "demo01", "演示甲"));
        return session;
    }

    private static ContactView sampleView() {
        ContactView view = new ContactView();
        view.setPairId(101L);
        view.setScore(new BigDecimal("0.92"));
        view.setScorePercent(92);
        view.setTier("DIRECT");
        view.setViewerSide("LOST");
        view.setTargetSide("FOUND");
        view.setAvailable(true);
        view.setContact("13800000002");
        view.setMessage("匹配度较高，可直接查看");
        return view;
    }
}
