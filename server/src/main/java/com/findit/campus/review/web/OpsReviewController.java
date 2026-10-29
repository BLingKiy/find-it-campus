package com.findit.campus.review.web;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.findit.campus.common.api.ApiResponse;
import com.findit.campus.common.api.ErrorCode;
import com.findit.campus.common.error.BizException;
import com.findit.campus.record.mapper.RecordMapper;
import com.findit.campus.review.web.dto.ReviewQueueItem;

/**
 * 审核队列的<b>只读</b>排障入口（不计入 17 个业务接口）。
 *
 * <p>人类 2026-10-29 拍板：<b>去掉人工改判</b>。这里只剩读：
 * 不允许任何通过本入口把 {@code PENDING} 改成 {@code APPROVED/REJECTED} 的能力，
 * 否则「AI 审核通过才上架」就多了一条绕过路径。
 * 唯一的人工兜底是<b>发布者本人</b>在 AI 连续失败后确认发布
 * （{@code POST /api/records/{id}/confirm-publish}），那条路径有所有权校验与 {@code OWNER_CONFIRM} 审计。
 *
 * <p>认证沿用 {@code OpsPairScanController} 的 {@code X-Ops-Token} 口径：未配置/传错一律 404，
 * 不区分"没配"与"错了"，也不暴露入口存在性。
 */
@RestController
@RequestMapping("/api/ops/reviews")
public class OpsReviewController {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    private final RecordMapper recordMapper;
    private final String opsToken;

    public OpsReviewController(RecordMapper recordMapper,
                               @Value("${OPS_SCAN_TOKEN:}") String opsToken) {
        this.recordMapper = recordMapper;
        this.opsToken = opsToken == null ? "" : opsToken.trim();
    }

    /** 等待发布者本人确认的队列（AI 连续失败的行）。只读，不改变任何审核结论。 */
    @GetMapping("/pending")
    public ApiResponse<List<ReviewQueueItem>> pending(
            @RequestHeader(value = "X-Ops-Token", required = false) String token,
            @RequestParam(value = "limit", defaultValue = "" + DEFAULT_LIMIT) int limit) {
        requireOps(token);
        int effective = Math.max(1, Math.min(limit, MAX_LIMIT));
        return ApiResponse.success(recordMapper.selectOwnerConfirmQueue(effective).stream()
                .map(ReviewQueueItem::of)
                .toList());
    }

    private void requireOps(String token) {
        if (opsToken.isEmpty() || !constantTimeEquals(opsToken, token)) {
            throw BizException.of(ErrorCode.RESOURCE_NOT_FOUND);
        }
    }

    static boolean constantTimeEquals(String expected, String actual) {
        if (actual == null) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }
}
