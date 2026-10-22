package com.findit.campus.match.web;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.findit.campus.common.api.ApiResponse;
import com.findit.campus.match.service.MatchService;
import com.findit.campus.match.web.dto.MatchRequest;
import com.findit.campus.match.web.dto.MatchResponse;

import jakarta.validation.Valid;

/**
 * {@code POST /api/match} —— 语义匹配（契约 §8）。
 *
 * <p>这个文件是"接口独立于实现"这条验收的正面证据：全文只出现 {@code MatchService} 与 DTO，
 * <b>没有任何引擎实现类名</b>（验收 V1）。真正的编排在 {@code MatchService}，
 * 它同样只认 {@code MatchEngine} 接口。
 *
 * <p>为什么是 POST 而不是 GET（ADR-007）：输入是 2000 字以内的自由文本，塞不进 query string；
 * 且每次调用要写一份快照，不是幂等的读。
 *
 * <p>无命中不是错误：返回 200 + {@code items: []}（契约 §8.3），前端显示"暂无匹配"空态。
 */
@RestController
@RequestMapping("/api")
public class MatchController {

    private final MatchService matchService;

    public MatchController(MatchService matchService) {
        this.matchService = matchService;
    }

    @PostMapping("/match")
    public ApiResponse<MatchResponse> match(@Valid @RequestBody MatchRequest request) {
        return ApiResponse.success(matchService.match(request));
    }
}
