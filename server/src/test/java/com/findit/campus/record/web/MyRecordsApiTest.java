package com.findit.campus.record.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.findit.campus.auth.domain.SessionUser;
import com.findit.campus.auth.session.SessionAuth;
import com.findit.campus.common.api.PageResult;
import com.findit.campus.record.domain.MyPairCounts;
import com.findit.campus.record.domain.MyRecordRow;
import com.findit.campus.record.domain.RecordStatus;
import com.findit.campus.record.domain.RecordType;
import com.findit.campus.record.service.MyRecordService;
import com.findit.campus.record.web.dto.MyRecordListItemResponse;
import com.findit.campus.record.web.dto.PairCard;
import com.findit.campus.support.PairCardFixtures;

/**
 * {@code GET /api/my/records} 的登录门禁与响应形状。
 *
 * <p>取代 B2 的 test-only 探针（{@code authprobe}）：本条落地真接口后，B2 证据 §8 的
 * "无 Cookie → 401"断言改打这里，不再需要占位路由。
 *
 * <p>匿名 401 由 {@code AuthGateFilter} 在路由之前给出（{@code /api/my} 已登记为私有读），
 * 因此 {@code MyRecordService} 一次都不该被调用；有会话时 publisherId 必须取自会话，不来自请求参数。
 */
@WebMvcTest(controllers = MyRecordsController.class)
@DisplayName("/api/my/records 登录门禁与所有者视角")
class MyRecordsApiTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MyRecordService myRecordService;

    @Test
    @DisplayName("无会话 → 401 + 10006 + 统一错误体（不是 404，也不是空列表）")
    void anonymousIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/my/records"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(10006))
                .andExpect(jsonPath("$.message").value("请先登录"))
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    @DisplayName("有会话 → 200，publisherId 取自 session，本人字段原文返回，pairs[] 逐条内嵌")
    void signedInReturnsOwnerScopedList() throws Exception {
        when(myRecordService.list(any(), eq(1L)))
                .thenReturn(PageResult.of(1, 10, 2, List.of(sampleItem(), itemWithoutPairs())));

        mockMvc.perform(get("/api/my/records").session(session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.list[0].id").value(23))
                .andExpect(jsonPath("$.data.list[0].status").value("COMPLETED"))
                // 本人数据不遮蔽：contact/publisherName/location 原文
                .andExpect(jsonPath("$.data.list[0].contact").value("13812345678"))
                .andExpect(jsonPath("$.data.list[0].publisherName").value("李同学"))
                .andExpect(jsonPath("$.data.list[0].location").value("第二教学楼楼梯口"))
                .andExpect(jsonPath("$.data.list[0].hasMatchedPair").value(true))
                .andExpect(jsonPath("$.data.list[0].matchedPairCount").value(1))
                .andExpect(jsonPath("$.data.list[0].detailVisible").value(false))
                // 卡片：字段与契约 §5.1 同名，服务端已定序
                .andExpect(jsonPath("$.data.list[0].pairs.length()").value(1))
                .andExpect(jsonPath("$.data.list[0].pairs[0].pairId").value(101))
                .andExpect(jsonPath("$.data.list[0].pairs[0].scorePercent").value(92))
                .andExpect(jsonPath("$.data.list[0].pairs[0].tier").value("DIRECT"))
                .andExpect(jsonPath("$.data.list[0].pairs[0].matched").value(true))
                .andExpect(jsonPath("$.data.list[0].pairs[0].counterpart.recordId").value(123))
                // 卡片永不含 contact 字段
                .andExpect(jsonPath("$.data.list[0].pairs[0].contact").doesNotExist())
                // 无有效 pair 的记录：pairs 是 []，不是省略
                .andExpect(jsonPath("$.data.list[1].pairs").isArray())
                .andExpect(jsonPath("$.data.list[1].pairs.length()").value(0));
        verify(myRecordService).list(any(), eq(1L));
    }

    @Test
    @DisplayName("status 取值非法（例如已作废的 CLAIMED）→ 400 + 10001 + field=status")
    void obsoleteStatusIsValidationError() throws Exception {
        mockMvc.perform(get("/api/my/records").param("status", "CLAIMED").session(session()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(10001))
                .andExpect(jsonPath("$.data.fieldErrors[0].field").value("status"));
    }

    private static MockHttpSession session() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionAuth.SESSION_ATTRIBUTE, new SessionUser(1L, "demo01", "演示甲"));
        return session;
    }

    private static MyRecordListItemResponse sampleItem() {
        MyRecordRow row = new MyRecordRow();
        row.setId(23L);
        row.setType(RecordType.FOUND);
        row.setStatus(RecordStatus.COMPLETED);
        row.setTitle("已归还：第二教学楼捡到的蓝色挂件钥匙");
        row.setLocation("第二教学楼楼梯口");
        row.setContact("13812345678");
        row.setPublisherName("李同学");
        row.setPairState("READY");
        row.setCompletedAt(LocalDateTime.of(2026, 10, 24, 9, 0));
        row.setOccurredAt(LocalDateTime.of(2026, 10, 12, 19, 0));
        row.setCreatedAt(LocalDateTime.of(2026, 10, 12, 19, 30));
        row.setUpdatedAt(LocalDateTime.of(2026, 10, 24, 9, 0));
        return MyRecordListItemResponse.of(row, new MyPairCounts(2, 1, 1, 1),
                List.of(PairCard.of(PairCardFixtures.direct(23L, 101L))));
    }

    /** 一条没有达阈值 pair 的记录：`pairs` 必须是 {@code []}，与"字段省略"区分开。 */
    private static MyRecordListItemResponse itemWithoutPairs() {
        MyRecordRow row = new MyRecordRow();
        row.setId(24L);
        row.setType(RecordType.LOST);
        row.setStatus(RecordStatus.PENDING);
        row.setTitle("丢了一个蓝色水杯");
        row.setLocation("图书馆二楼");
        row.setPairState("READY");
        row.setOccurredAt(LocalDateTime.of(2026, 10, 12, 19, 0));
        row.setCreatedAt(LocalDateTime.of(2026, 10, 12, 19, 30));
        row.setUpdatedAt(LocalDateTime.of(2026, 10, 12, 19, 30));
        return MyRecordListItemResponse.of(row, new MyPairCounts(0, 0, 0, 0), List.of());
    }
}
