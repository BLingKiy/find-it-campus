package com.findit.campus.review.web;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.findit.campus.common.api.ApiResponse;
import com.findit.campus.common.api.ErrorCode;
import com.findit.campus.common.api.RequestContext;
import com.findit.campus.common.error.BizException;
import com.findit.campus.record.domain.Record;
import com.findit.campus.record.mapper.RecordMapper;
import com.findit.campus.review.service.ReviewPersistence;
import com.findit.campus.review.web.dto.ReviewDecisionRequest;
import com.findit.campus.review.web.dto.ReviewQueueItem;

import jakarta.validation.Valid;

/**
 * 人工审核入口（不计入 17 个业务接口）。
 *
 * <p>认证沿用 {@code OpsPairScanController} 的 {@code X-Ops-Token} 口径：未配置/传错一律 404，
 * 不区分"没配"与"错了"，也不暴露入口存在性。它<b>不是</b>给普通用户的管理后台；
 * 正式环境应换成独立审核员角色（见 ADR 开放项 4）。
 *
 * <p>人工放行只改审核列，不重新生成展示文本：提交时落库的已经是规则剥离后的展示文本，
 * 人工确认的是"这份文本可以上架"。放行后由事务内事件触发打分。
 */
@RestController
@RequestMapping("/api/ops/reviews")
public class OpsReviewController {

    private static final Logger log = LoggerFactory.getLogger(OpsReviewController.class);
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    private final RecordMapper recordMapper;
    private final ReviewPersistence reviewPersistence;
    private final String opsToken;

    public OpsReviewController(RecordMapper recordMapper,
                               ReviewPersistence reviewPersistence,
                               @Value("${OPS_SCAN_TOKEN:}") String opsToken) {
        this.recordMapper = recordMapper;
        this.reviewPersistence = reviewPersistence;
        this.opsToken = opsToken == null ? "" : opsToken.trim();
    }

    /** 待人工队列（只含 AI 连续失败转人工的行）。 */
    @GetMapping("/pending")
    public ApiResponse<List<ReviewQueueItem>> pending(
            @RequestHeader(value = "X-Ops-Token", required = false) String token,
            @RequestParam(value = "limit", defaultValue = "" + DEFAULT_LIMIT) int limit) {
        requireOps(token);
        int effective = Math.max(1, Math.min(limit, MAX_LIMIT));
        return ApiResponse.success(recordMapper.selectManualReviewQueue(effective).stream()
                .map(ReviewQueueItem::of)
                .toList());
    }

    @PostMapping("/{id}/approve")
    public ApiResponse<ReviewQueueItem> approve(@PathVariable long id,
                                                 @RequestHeader(value = "X-Ops-Token", required = false) String token,
                                                 @Valid @RequestBody ReviewDecisionRequest request) {
        requireOps(token);
        if (!reviewPersistence.manualApprove(id, request.getReason(), 0L, RequestContext.requestId())) {
            throw BizException.of(ErrorCode.RECORD_OPERATION_NOT_ALLOWED,
                    "该记录不处于待审状态，无法人工放行");
        }
        log.info("review manual approve recordId={} reason={}", id, request.getReason());
        return ApiResponse.success(ReviewQueueItem.of(require(id)), "已放行");
    }

    @PostMapping("/{id}/reject")
    public ApiResponse<ReviewQueueItem> reject(@PathVariable long id,
                                                @RequestHeader(value = "X-Ops-Token", required = false) String token,
                                                @Valid @RequestBody ReviewDecisionRequest request) {
        requireOps(token);
        if (!reviewPersistence.manualReject(id, request.getReason(), 0L, RequestContext.requestId())) {
            throw BizException.of(ErrorCode.RECORD_OPERATION_NOT_ALLOWED,
                    "该记录不处于待审状态，无法人工驳回");
        }
        log.info("review manual reject recordId={} reason={}", id, request.getReason());
        return ApiResponse.success(ReviewQueueItem.of(require(id)), "已驳回");
    }

    private Record require(long id) {
        Record record = recordMapper.selectById(id);
        if (record == null) {
            throw BizException.of(ErrorCode.RECORD_NOT_FOUND);
        }
        return record;
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
