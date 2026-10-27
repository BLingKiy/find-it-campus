package com.findit.campus.record.web;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
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
import com.findit.campus.common.api.PageResult;
import com.findit.campus.record.domain.RecordStatus;
import com.findit.campus.record.domain.RecordType;
import com.findit.campus.record.service.RecordService;
import com.findit.campus.record.web.dto.RecordDetailResponse;
import com.findit.campus.record.web.dto.RecordListItemResponse;

/**
 * {@code /api/records} 的入参校验与响应形状（契约 §3 / §5 / §9）。
 *
 * <p>用 {@code @WebMvcTest} 而不是起全量上下文：这一层的验收点是"错误路径返回约定错误码而不是 500 堆栈"
 * 与"响应体字段与契约一致"，与数据库无关；service 全部 mock 掉，测试就不需要 MySQL 也能跑。
 *
 * <p>两组容易漏的出口在这里被钉住：
 * <ol>
 *   <li>查询参数收成 POJO（{@code RecordQuery}）后，Spring 抛的是 {@code BindException} 而不是
 *       {@code MethodArgumentNotValidException} —— 少了那条出口就会静默变成 10005/500；</li>
 *   <li>路径 id 非数字走类型不符出口，必须给 {@code field=id} 而不是通用错误。</li>
 * </ol>
 */
@WebMvcTest(controllers = RecordController.class)
@DisplayName("/api/records 入参校验与响应形状")
class RecordApiValidationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RecordService recordService;

    /**
     * 已登录会话：B2 起写接口（含 {@code POST /api/records}）要求登录，本类验的是"入参校验"，
     * 不该被鉴权挡住，所以 POST 用例统一挂这个会话。
     * "无会话 → 401"是独立用例（{@link #postWithoutSessionIsUnauthorized()}），两者不混在一起验。
     */
    private MockHttpSession session;

    @BeforeEach
    void signIn() {
        session = new MockHttpSession();
        session.setAttribute(SessionAuth.SESSION_ATTRIBUTE, new SessionUser(1L, "demo01", "演示甲"));
    }

    @Test
    @DisplayName("GET 列表：status 取值非法 → 400 + 10001 + fieldErrors（不是 500）")
    void illegalStatusIsValidationError() throws Exception {
        mockMvc.perform(get("/api/records").param("status", "BOGUS"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(10001))
                .andExpect(jsonPath("$.data.fieldErrors[0].field").value("status"));
        verifyNoInteractions(recordService);
    }

    @Test
    @DisplayName("GET 列表：pageSize 超过 50 → 400 + 10001")
    void pageSizeOverLimit() throws Exception {
        mockMvc.perform(get("/api/records").param("pageSize", "51"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(10001))
                .andExpect(jsonPath("$.data.fieldErrors[0].field").value("pageSize"));
    }

    @Test
    @DisplayName("GET 列表：sortBy 不在白名单 → 400 + 10001（ADR-014 的入口就在 DTO 注解上）")
    void sortByOutsideWhitelist() throws Exception {
        mockMvc.perform(get("/api/records").param("sortBy", "id"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(10001))
                .andExpect(jsonPath("$.data.fieldErrors[0].field").value("sortBy"));
    }

    @Test
    @DisplayName("GET 详情：路径 id 非数字 → 400 + 10001 + field=id（契约 §4）")
    void nonNumericIdIsValidationError() throws Exception {
        mockMvc.perform(get("/api/records/new"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(10001))
                .andExpect(jsonPath("$.data.fieldErrors[0].field").value("id"))
                .andExpect(jsonPath("$.data.fieldErrors[0].reason").value("必须为数字"));
    }

    @Test
    @DisplayName("POST 提交：请求体不是合法 JSON → 400 + 10001 + fieldErrors 非空（不再是空数组）")
    void malformedJsonBodyCarriesFieldErrors() throws Exception {
        mockMvc.perform(post("/api/records").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(10001))
                // 契约 §6.2：10001 必须带 fieldErrors[{field,reason}]；空数组等于用户看不到原因
                .andExpect(jsonPath("$.data.fieldErrors.length()").value(1))
                .andExpect(jsonPath("$.data.fieldErrors[0].field").value("body"))
                .andExpect(jsonPath("$.data.fieldErrors[0].reason").isNotEmpty());
        verifyNoInteractions(recordService);
    }

    @Test
    @DisplayName("POST 提交：请求体缺失 → 400 + 10001 + fieldErrors 非空")
    void missingBodyCarriesFieldErrors() throws Exception {
        mockMvc.perform(post("/api/records").session(session).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(10001))
                .andExpect(jsonPath("$.data.fieldErrors.length()").value(1))
                .andExpect(jsonPath("$.data.fieldErrors[0].field").value("body"))
                .andExpect(jsonPath("$.data.fieldErrors[0].reason").isNotEmpty());
        verifyNoInteractions(recordService);
    }

    @Test
    @DisplayName("POST 提交：某字段类型不符 → 400 + 10001 + fieldErrors 指向该字段（能定位就不退回 body）")
    void wrongFieldTypePointsAtTheField() throws Exception {
        mockMvc.perform(post("/api/records").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"LOST","title":"丢了一个灰色背包","description":"今天下午在第一教学楼 305 落下","location":"第一教学楼305","occurredAt":"2026-10-21 15:20:00","categoryId":{"nested":1}}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(10001))
                .andExpect(jsonPath("$.data.fieldErrors[*].field", containsInAnyOrder("categoryId")));
        verifyNoInteractions(recordService);
    }

    @Test
    @DisplayName("POST 提交：缺必填字段 → 400 + 10001，fieldErrors 一次给全（契约 §1.1 / §5.3）")
    void missingRequiredFieldsReturnAllFieldErrors() throws Exception {
        mockMvc.perform(post("/api/records").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"LOST\",\"title\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(10001))
                .andExpect(jsonPath("$.data.fieldErrors[*].field",
                        containsInAnyOrder("title", "description", "location", "occurredAt")));
        verifyNoInteractions(recordService);
    }

    @Test
    @DisplayName("POST 提交：occurredAt 晚于当前时间超过容差 → 400 + 10001 + field=occurredAt（契约 §5.1 / Q-08）")
    void futureOccurredAtIsRejected() throws Exception {
        String future = LocalDateTime.now().plusDays(1)
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        mockMvc.perform(post("/api/records").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"LOST","title":"丢了一个灰色背包","description":"今天下午在第一教学楼 305 落下","location":"第一教学楼305","occurredAt":"%s"}
                                """.formatted(future)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(10001))
                .andExpect(jsonPath("$.data.fieldErrors[0].field").value("occurredAt"));
    }

    @Test
    @DisplayName("POST 提交：occurredAt 日历非法（13 月 / 2 月 30 日 / 非闰年 2 月 29 日）→ 400 + 10001 + 只报一条 field=occurredAt（DEF-01）")
    void calendarInvalidOccurredAtIsValidationError() throws Exception {
        // 形状全部合法（正则放行），只有日历不成立；这类值曾经漏到 service 的 parse 才炸成 10005/500，
        // 其中 2026-02-30 更糟 —— SMART 解析会把它悄悄纠成 2026-02-28 落库。
        for (String value : List.of("2026-13-40 25:61:61", "2026-02-30 10:00:00", "2025-02-29 10:00:00")) {
            mockMvc.perform(post("/api/records").session(session)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"type":"LOST","title":"丢了一个灰色背包","description":"今天下午在第一教学楼 305 落下","location":"第一教学楼305","occurredAt":"%s"}
                                    """.formatted(value)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(10001))
                    // 恰好一条：形状/日历/未来三个约束各管一条规则，同一个输入不许报两次
                    .andExpect(jsonPath("$.data.fieldErrors[*].field", containsInAnyOrder("occurredAt")));
        }
        verifyNoInteractions(recordService);
    }

    @Test
    @DisplayName("POST 提交：纯空格标题按未填写处理（去首尾空格后判长，契约 §5.1）")
    void blankTitleIsRejected() throws Exception {
        mockMvc.perform(post("/api/records").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"LOST","title":"   ","description":"今天下午在第一教学楼 305 落下","location":"第一教学楼305","occurredAt":"2026-10-21 15:20:00"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(10001))
                .andExpect(jsonPath("$.data.fieldErrors[*].field", containsInAnyOrder("title")));
    }

    @Test
    @DisplayName("GET 列表成功：响应体字段与契约 §3.2 一致，时间格式为 yyyy-MM-dd HH:mm:ss")
    void listResponseShape() throws Exception {
        RecordListItemResponse item = new RecordListItemResponse();
        // 走 from() 需要 domain 对象，这里用测试内的最小构造：直接验证外壳字段
        when(recordService.list(any())).thenReturn(PageResult.of(1, 10, 1, List.of(sampleItem())));

        mockMvc.perform(get("/api/records"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.pageSize").value(10))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.totalPages").value(1))
                .andExpect(jsonPath("$.data.hasNext").value(false))
                .andExpect(jsonPath("$.data.list[0].id").value(13))
                .andExpect(jsonPath("$.data.list[0].type").value("FOUND"))
                .andExpect(jsonPath("$.data.list[0].status").value("PENDING"))
                .andExpect(jsonPath("$.data.list[0].categoryName").value("钱包卡证"))
                .andExpect(jsonPath("$.data.list[0].occurredAt").value("2026-10-20 15:00:00"))
                .andExpect(jsonPath("$.data.list[0].createdAt").value("2026-10-20 15:30:00"))
                // 列表刻意不返回 description / contact / publisherName（契约 §3.2）
                .andExpect(jsonPath("$.data.list[0].description").doesNotExist())
                .andExpect(jsonPath("$.data.list[0].contact").doesNotExist());
    }

    @Test
    @DisplayName("空结果：200 + list 空数组，不是 404（契约 §1「空结果」）")
    void emptyListIsOk() throws Exception {
        when(recordService.list(any())).thenReturn(PageResult.of(1, 10, 0, List.of()));

        mockMvc.perform(get("/api/records").param("keyword", "不存在的东西"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.totalPages").value(0))
                .andExpect(jsonPath("$.data.hasNext").value(false))
                .andExpect(jsonPath("$.data.list.length()").value(0));
    }

    @Test
    @DisplayName("详情成功：含 description/contact/allowedStatuses（契约 §4）")
    void detailResponseShape() throws Exception {
        when(recordService.detail(13L)).thenReturn(RecordDetailResponse.from(sampleRecord()));

        mockMvc.perform(get("/api/records/13"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value(13))
                .andExpect(jsonPath("$.data.description").value("黑色皮质钱包，内有银行卡两张"))
                .andExpect(jsonPath("$.data.contact").value("13800000101"))
                .andExpect(jsonPath("$.data.allowedStatuses", containsInAnyOrder("CLAIMED", "CLOSED")))
                .andExpect(jsonPath("$.data.occurredAt").value("2026-10-20 15:00:00"));
    }

    @Test
    @DisplayName("POST 提交：无会话 → 401 + 10006 + 统一错误体（不是 500，不是空列表，也不是 400）")
    void postWithoutSessionIsUnauthorized() throws Exception {
        mockMvc.perform(post("/api/records")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"LOST","title":"丢了一个灰色背包","description":"今天下午在第一教学楼 305 落下","location":"第一教学楼305","occurredAt":"2026-10-21 15:20:00"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(10006))
                .andExpect(jsonPath("$.message").value("请先登录"))
                // 错误体与成功体同形状：data 为 null（不是缺字段，也不是空列表）
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
        // 鉴权在入口就拦下：service 一次都不该被调用（否则等于"先写后判"，会留下脏数据）
        verifyNoInteractions(recordService);
    }

    @Test
    @DisplayName("GET 列表：无会话仍匿名可读 → 200（读默认放、写默认拦）")
    void listStaysAnonymousWithoutSession() throws Exception {
        when(recordService.list(any())).thenReturn(PageResult.of(1, 10, 0, List.of()));

        mockMvc.perform(get("/api/records"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    private static com.findit.campus.record.domain.Record sampleRecord() {
        com.findit.campus.record.domain.Record record = new com.findit.campus.record.domain.Record();
        record.setId(13L);
        record.setType(RecordType.FOUND);
        record.setStatus(RecordStatus.PENDING);
        record.setTitle("图书馆拾到黑色钱包");
        record.setDescription("黑色皮质钱包，内有银行卡两张");
        record.setCategoryId(1L);
        record.setCategoryName("钱包卡证");
        record.setLocation("图书馆三楼自习区");
        record.setOccurredAt(LocalDateTime.of(2026, 10, 20, 15, 0, 0));
        record.setContact("13800000101");
        record.setPublisherName("周同学");
        record.setCreatedAt(LocalDateTime.of(2026, 10, 20, 15, 30, 0));
        record.setUpdatedAt(LocalDateTime.of(2026, 10, 20, 15, 30, 0));
        return record;
    }

    private static RecordListItemResponse sampleItem() {
        com.findit.campus.record.domain.RecordSummary summary = summary();
        return RecordListItemResponse.from(summary);
    }

    private static com.findit.campus.record.domain.RecordSummary summary() {
        com.findit.campus.record.domain.RecordSummary summary = new com.findit.campus.record.domain.RecordSummary();
        summary.setId(13L);
        summary.setType(RecordType.FOUND);
        summary.setStatus(RecordStatus.PENDING);
        summary.setTitle("图书馆拾到黑色钱包");
        summary.setCategoryId(1L);
        summary.setCategoryName("钱包卡证");
        summary.setLocation("图书馆三楼自习区");
        summary.setOccurredAt(LocalDateTime.of(2026, 10, 20, 15, 0, 0));
        summary.setCreatedAt(LocalDateTime.of(2026, 10, 20, 15, 30, 0));
        return summary;
    }
}
