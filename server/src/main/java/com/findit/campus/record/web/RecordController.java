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

import com.findit.campus.common.api.ApiResponse;
import com.findit.campus.common.api.PageResult;
import com.findit.campus.record.service.RecordService;
import com.findit.campus.record.web.dto.RecordCreateRequest;
import com.findit.campus.record.web.dto.RecordDetailResponse;
import com.findit.campus.record.web.dto.RecordListItemResponse;
import com.findit.campus.record.web.dto.RecordQuery;
import com.findit.campus.record.web.dto.StatusUpdateRequest;
import com.findit.campus.record.web.dto.StatusUpdateResponse;

import jakarta.validation.Valid;

/**
 * {@code /api/records} —— 记录的六个 REST 接口（契约 §3–§6）。
 *
 * <p>controller 的职责边界（§6）：绑参数 → {@code @Valid} → 调 service → 把结果包成 {@link ApiResponse}。
 * 这里不写业务规则、不注入 Mapper、不 catch 异常后自己挑错误码 —— 错误码只有
 * {@code GlobalExceptionHandler} 一个出口，才不会两处写歪。
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

    /** 列表：筛选 + 分页 + 排序（契约 §3）。空结果返回 {@code list: []}，不是 404。 */
    @GetMapping
    public ApiResponse<PageResult<RecordListItemResponse>> list(@Valid @ModelAttribute RecordQuery query) {
        return ApiResponse.success(recordService.list(query));
    }

    /** 详情（契约 §4）。不存在/已删除 → 404 + 20001。 */
    @GetMapping("/{id}")
    public ApiResponse<RecordDetailResponse> detail(@PathVariable long id) {
        return ApiResponse.success(recordService.detail(id));
    }

    /** 提交（契约 §5）。返回与详情同构的完整对象，含 id 供前端跳详情页。 */
    @PostMapping
    public ApiResponse<RecordDetailResponse> create(@Valid @RequestBody RecordCreateRequest request) {
        return ApiResponse.success(recordService.create(request), "已提交");
    }

    /** 全量修改内容（契约 §6）。不改 status；{@code CLOSED} 记录 → 20003。 */
    @PutMapping("/{id}")
    public ApiResponse<RecordDetailResponse> update(@PathVariable long id,
                                                    @Valid @RequestBody RecordCreateRequest request) {
        return ApiResponse.success(recordService.update(id, request), "已更新");
    }

    /** 状态流转（契约 §6.1）。非法流转 → 409 + 20002。 */
    @PatchMapping("/{id}/status")
    public ApiResponse<StatusUpdateResponse> updateStatus(@PathVariable long id,
                                                          @Valid @RequestBody StatusUpdateRequest request) {
        return ApiResponse.success(recordService.updateStatus(id, request), "状态已更新");
    }

    /**
     * 逻辑删除（契约 §6.2）。
     *
     * <p>用 200 + body 而不是 204：契约 §1 要求"所有响应同结构"，204 无 body 会逼前端为空体写特例。
     */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable long id) {
        recordService.delete(id);
        return ApiResponse.success(null, "已删除");
    }
}
