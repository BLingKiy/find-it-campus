package com.findit.campus.record.web;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.findit.campus.auth.domain.SessionUser;
import com.findit.campus.auth.session.SessionAuth;
import com.findit.campus.common.api.ApiResponse;
import com.findit.campus.common.api.PageResult;
import com.findit.campus.record.service.RecordService;
import com.findit.campus.record.web.dto.RecordCreateRequest;
import com.findit.campus.record.web.dto.RecordDetailResponse;
import com.findit.campus.record.web.dto.RecordListItemResponse;
import com.findit.campus.record.web.dto.RecordQuery;
import com.findit.campus.record.web.dto.StatusUpdateRequest;
import com.findit.campus.record.web.dto.StatusUpdateResponse;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

/**
 * {@code /api/records} —— 记录的六个 REST 接口（契约 §4）。
 *
 * <p>controller 的职责边界：绑参数 → {@code @Valid} → 从会话取身份 → 调 service → 包
 * {@link ApiResponse}。这里不写业务规则、不注入 Mapper、不 catch 异常后自己捞错误码 ——
 * 错误码只有一个出口 {@code GlobalExceptionHandler}，才不会两处写歪。
 *
 * <p><b>身份从会话取，不从请求体/查询参数取</b>：读接口允许匿名（{@code currentUser} 可能为 null，
 * 下游按"匿名"遮蔽）；写接口用 {@code requireUser}，未登录 401。门禁 Filter 已经拦过一次，
 * 这里再取一次是纵深防御（Filter 被绕过时不会把"无身份"写成 publisher_id）。
 *
 * <p>为什么不用 {@code ResponseEntity} 手填状态码：成功路径恒 200，失败路径的 HTTP 状态码由
 * {@code ErrorCode.httpStatus} 决定（ADR-005），controller 手填就等于绕过这张映射表。
 */
@RestController
@RequestMapping("/api/records")
public class RecordController {

    private final RecordService recordService;

    public RecordController(RecordService recordService) {
        this.recordService = recordService;
    }

    /** 列表：筛选 + 分页 + 排序；空结果 200 + {@code list: []}，不是 404。 */
    @GetMapping
    public ApiResponse<PageResult<RecordListItemResponse>> list(@Valid @ModelAttribute RecordQuery query,
                                                                HttpServletRequest request) {
        SessionUser viewer = SessionAuth.currentUser(request);
        return ApiResponse.success(recordService.list(query, viewer == null ? null : viewer.id()));
    }

    /** 详情：匿名可读（PENDING 遮蔽）；终态按可见性规则 404。 */
    @GetMapping("/{id}")
    public ApiResponse<RecordDetailResponse> detail(@PathVariable long id, HttpServletRequest request) {
        SessionUser viewer = SessionAuth.currentUser(request);
        return ApiResponse.success(recordService.detail(id, viewer == null ? null : viewer.id()));
    }

    /** 提交：publisher_id 取 session；status 固定 PENDING。 */
    @PostMapping
    public ApiResponse<RecordDetailResponse> create(@Valid @RequestBody RecordCreateRequest request,
                                                    HttpServletRequest httpRequest) {
        SessionUser publisher = SessionAuth.requireUser(httpRequest);
        return ApiResponse.success(recordService.create(request, publisher), "已提交");
    }

    /** 全量修改：仅所有者且 PENDING；非所有者 403/404 分流。 */
    @PutMapping("/{id}")
    public ApiResponse<RecordDetailResponse> update(@PathVariable long id,
                                                    @Valid @RequestBody RecordCreateRequest request,
                                                    HttpServletRequest httpRequest) {
        long viewerId = SessionAuth.requireUser(httpRequest).id();
        return ApiResponse.success(recordService.update(id, request, viewerId), "已更新");
    }

    /** 状态流转：三态唯一写入路径（标记完成 / 关闭）。 */
    @PatchMapping("/{id}/status")
    public ApiResponse<StatusUpdateResponse> updateStatus(@PathVariable long id,
                                                          @Valid @RequestBody StatusUpdateRequest request,
                                                          HttpServletRequest httpRequest) {
        long viewerId = SessionAuth.requireUser(httpRequest).id();
        StatusUpdateResponse response = recordService.updateStatus(id, request, viewerId);
        String message = "COMPLETED".equals(response.getStatus()) ? "已标记完成" : "已关闭";
        return ApiResponse.success(response, message);
    }

    /** 逻辑删除：三态都可删；只写 deleted_at，不改 status。 */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable long id, HttpServletRequest request) {
        long viewerId = SessionAuth.requireUser(request).id();
        recordService.delete(id, viewerId);
        return ApiResponse.success(null, "已删除");
    }
}
