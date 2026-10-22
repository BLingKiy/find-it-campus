package com.findit.campus.common.error;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

import com.findit.campus.validationprobe.MethodValidationProbeController;
import com.findit.campus.validationprobe.ValidatedProbeController;

/**
 * 方法级参数校验的失败出口（基线 §6.4）。
 *
 * <p>要钉死三件事，少一件都算出口漏了：
 * <ol>
 *   <li>响应是 <b>HTTP 400</b>，不是 500；</li>
 *   <li>body 是统一 {@code ApiResponse} 形状且 {@code code=10001}，<b>不是</b> 10005、也不是 Spring 的 ProblemDetail；</li>
 *   <li>多个参数同时违规时 {@code fieldErrors} 返回<b>全部</b>违规字段。</li>
 * </ol>
 *
 * <p>controller 来自测试作用域的探针（{@code com.findit.campus.validationprobe}），不进主代码。
 */
// 只装配两个探针 controller：默认的 @WebMvcTest 会把 HealthController 一起扫进来，
// 而它依赖 DataSource / MatchEngineConfig —— 本测试只关心校验出口，不关心 DB。
// GlobalExceptionHandler 是 @ControllerAdvice，不受 controllers 过滤影响，仍会生效。
@WebMvcTest(controllers = {MethodValidationProbeController.class, ValidatedProbeController.class})
@DisplayName("方法级参数校验失败 → HTTP 400 + code 10001 + 全量 fieldErrors")
class MethodValidationExitTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("Spring MVC 内建方法校验：单字段违规 → 400 / 10001 / fieldErrors 含该字段")
    void singleFieldViolation() throws Exception {
        mockMvc.perform(get("/probe/method-validation").param("page", "0").param("size", "10"))
                .andDo(print())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(10001))
                .andExpect(jsonPath("$.message").value("请求参数校验失败"))
                .andExpect(jsonPath("$.data.fieldErrors.length()").value(1))
                .andExpect(jsonPath("$.data.fieldErrors[0].field").value("page"))
                .andExpect(jsonPath("$.data.fieldErrors[0].reason").value("页码从 1 开始"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    @DisplayName("Spring MVC 内建方法校验：多字段同时违规 → fieldErrors 返回全部，不只第一条")
    void allFieldsReturned() throws Exception {
        mockMvc.perform(get("/probe/method-validation").param("page", "0").param("size", "999"))
                .andDo(print())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(10001))
                .andExpect(jsonPath("$.data.fieldErrors.length()").value(2))
                .andExpect(jsonPath("$.data.fieldErrors[*].field", containsInAnyOrder("page", "size")))
                .andExpect(jsonPath("$.data.fieldErrors[*].reason",
                        containsInAnyOrder("页码从 1 开始", "每页最多 50 条")));
    }

    @Test
    @DisplayName("@Pattern 违规的 @RequestParam → 400 / 10001")
    void patternViolationOnRequestParam() throws Exception {
        mockMvc.perform(get("/probe/method-validation/keyword").param("keyword", "a"))
                .andDo(print())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(10001))
                .andExpect(jsonPath("$.data.fieldErrors[0].field").value("keyword"));
    }

    @Test
    @DisplayName("@Min 违规的 @PathVariable → 400 / 10001")
    void minViolationOnPathVariable() throws Exception {
        mockMvc.perform(get("/probe/method-validation/0"))
                .andDo(print())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(10001))
                .andExpect(jsonPath("$.data.fieldErrors[0].field").value("id"));
    }

    @Test
    @DisplayName("类级 @Validated 路径（ConstraintViolationException）：多字段违规 → 400 / 10001 / 全量 fieldErrors")
    void validatedProxyPath() throws Exception {
        mockMvc.perform(get("/probe/validated").param("page", "0").param("size", "999"))
                .andDo(print())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(10001))
                .andExpect(jsonPath("$.data.fieldErrors.length()").value(2))
                .andExpect(jsonPath("$.data.fieldErrors[*].field", containsInAnyOrder("page", "size")));
    }

    @Test
    @DisplayName("参数合法时正常 200，校验出口没有误伤正常请求")
    void legalParamsPassThrough() throws Exception {
        mockMvc.perform(get("/probe/method-validation").param("page", "1").param("size", "10"))
                .andExpect(status().isOk());
    }
}
