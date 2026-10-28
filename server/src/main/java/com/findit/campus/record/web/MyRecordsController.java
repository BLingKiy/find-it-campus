package com.findit.campus.record.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.findit.campus.auth.session.SessionAuth;
import com.findit.campus.common.api.ApiResponse;
import com.findit.campus.common.api.PageResult;
import com.findit.campus.record.service.MyRecordService;
import com.findit.campus.record.web.dto.MyRecordListItemResponse;
import com.findit.campus.record.web.dto.MyRecordsQuery;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

/**
 * {@code GET /api/my/records} —— 我的启事（契约 §4.12）。
 *
 * <p>登录门禁由 {@code AuthGateFilter} 在路由之前完成（{@code /api/my} 已登记为私有读），
 * 这里再调一次 {@link SessionAuth#requireUser} 是纵深防御：controller 可能被新的路由方式
 * （例如内部转发、测试直调）绕过 Filter，而"取不到用户就 401"这条规则不能依赖 Filter 的存在。
 *
 * <p>取代 B2 的 test-only 探针 {@code authprobe.MyRecordsProbeController}：
 * 探针已删除，门禁断言改打这个真接口（B2 证据 §8 的约定）。
 */
@RestController
@RequestMapping("/api/my")
public class MyRecordsController {

    private final MyRecordService myRecordService;

    public MyRecordsController(MyRecordService myRecordService) {
        this.myRecordService = myRecordService;
    }

    @GetMapping("/records")
    public ApiResponse<PageResult<MyRecordListItemResponse>> list(@Valid @ModelAttribute MyRecordsQuery query,
                                                                  HttpServletRequest request) {
        long publisherId = SessionAuth.requireUser(request).id();
        return ApiResponse.success(myRecordService.list(query, publisherId));
    }
}
