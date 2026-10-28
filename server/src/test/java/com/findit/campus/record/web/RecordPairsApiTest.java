package com.findit.campus.record.web;

import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.findit.campus.record.domain.Record;
import com.findit.campus.record.domain.RecordStatus;
import com.findit.campus.record.domain.RecordType;
import com.findit.campus.record.service.RecordService;
import com.findit.campus.record.web.dto.PairCard;
import com.findit.campus.record.web.dto.RecordDetailResponse;
import com.findit.campus.support.PairCardFixtures;

/**
 * 详情出口的 {@code pairs[]}（契约 §4.2 / §5.1）：真实 DTO 序列化，不是 mock 的 JSON。
 *
 * <p>两个视角的差别是本类的重点：所有者拿到 {@code pairs}（可以是空数组），
 * 非所有者 <b>没有这个键</b>。用 {@code jsonPath(...).doesNotExist()} 而不是断言 {@code []}，
 * 因为"字段省略"和"空数组"对前端是两种信号。
 *
 * <p>号码 0 命中用整份响应体做正则，夹具里除了号码没有任何数字串；本人记录的 {@code contact}
 * 为空，因此命中与否只取决于卡片有没有把对方号码带出来。
 */
@WebMvcTest(controllers = RecordController.class)
@DisplayName("GET /api/records/{id}：所有者 pairs[] / 非所有者字段省略")
class RecordPairsApiTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RecordService recordService;

    @Test
    @DisplayName("所有者：pairs 按服务端顺序返回，字段同名；卡片无 contact（号码正则 0 命中）")
    void ownerDetailCarriesOrderedPairsWithoutContact() throws Exception {
        when(recordService.detail(eq(13L), any())).thenReturn(RecordDetailResponse.ownerView(
                ownerRecord(), 0, 1,
                List.of(
                        PairCard.of(PairCardFixtures.sensitive(13L, 101L)),
                        PairCard.of(PairCardFixtures.authorizableNotPublic(13L, 102L)))));

        MvcResult result = mockMvc.perform(get("/api/records/13"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isMine").value(true))
                .andExpect(jsonPath("$.data.pairs.length()").value(2))
                .andExpect(jsonPath("$.data.pairs[0].pairId").value(101))
                .andExpect(jsonPath("$.data.pairs[0].scorePercent").value(95))
                .andExpect(jsonPath("$.data.pairs[0].tier").value("DIRECT"))
                .andExpect(jsonPath("$.data.pairs[0].matched").value(true))
                .andExpect(jsonPath("$.data.pairs[0].myConfirmed").value(true))
                .andExpect(jsonPath("$.data.pairs[0].otherConfirmed").value(true))
                .andExpect(jsonPath("$.data.pairs[0].lostConfirmedAt").value("2026-10-27 09:00:00"))
                .andExpect(jsonPath("$.data.pairs[0].foundConfirmedAt").value("2026-10-27 09:10:00"))
                .andExpect(jsonPath("$.data.pairs[0].counterpart.recordId").value(113))
                .andExpect(jsonPath("$.data.pairs[0].counterpart.title").value("图书馆捡到钱包 [手机号已隐藏]"))
                .andExpect(jsonPath("$.data.pairs[0].counterpart.location").value("第三教学楼…"))
                .andExpect(jsonPath("$.data.pairs[0].myNote").value("周六图书馆见 [手机号已隐藏]"))
                .andExpect(jsonPath("$.data.pairs[0].otherNote").value("我在前台 [手机号已隐藏]"))
                .andExpect(jsonPath("$.data.pairs[0].contactAvailable").value(true))
                .andExpect(jsonPath("$.data.pairs[0].contactHint").value("匹配度较高，可直接查看"))
                .andExpect(jsonPath("$.data.pairs[0].contact").doesNotExist())
                // 两条卡片各自独立：第二条是低分档未公开
                .andExpect(jsonPath("$.data.pairs[1].pairId").value(102))
                .andExpect(jsonPath("$.data.pairs[1].tier").value("AUTHORIZABLE"))
                .andExpect(jsonPath("$.data.pairs[1].degraded").value(true))
                .andExpect(jsonPath("$.data.pairs[1].contactAvailable").value(false))
                .andExpect(jsonPath("$.data.pairs[1].contactHint").value("对方尚未公开"))
                .andReturn();

        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertFalse(body.matches("(?s).*1[3-9]\\d{9}.*"),
                "响应体不得出现号码片段（卡片只给按钮状态）：" + body);
    }

    @Test
    @DisplayName("非所有者/匿名：pairs 键不存在，lowConfidenceCount=0")
    void maskedDetailOmitsPairsField() throws Exception {
        when(recordService.detail(eq(13L), any())).thenReturn(RecordDetailResponse.from(ownerRecord()));

        mockMvc.perform(get("/api/records/13"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isMine").value(false))
                .andExpect(jsonPath("$.data.contact").value(nullValue()))
                .andExpect(jsonPath("$.data.pairs").doesNotExist())
                .andExpect(jsonPath("$.data.lowConfidenceCount").value(0));
    }

    private static Record ownerRecord() {
        Record record = new Record();
        record.setId(13L);
        record.setType(RecordType.FOUND);
        record.setStatus(RecordStatus.PENDING);
        record.setTitle("图书馆拾到黑色钱包");
        record.setDescription("黑色皮质钱包");
        record.setLocation("图书馆三楼自习区");
        record.setOccurredAt(LocalDateTime.of(2026, 10, 20, 15, 0));
        // 本人没填联系方式：让整份响应体的号码扫描只可能命中"卡片泄露"
        record.setContact(null);
        record.setPublisherName("周同学");
        record.setPublisherId(1L);
        record.setPairState("READY");
        record.setCreatedAt(LocalDateTime.of(2026, 10, 20, 15, 30));
        record.setUpdatedAt(LocalDateTime.of(2026, 10, 20, 15, 30));
        return record;
    }
}
