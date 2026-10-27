package com.findit.campus.pair.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.findit.campus.common.api.ErrorCode;
import com.findit.campus.common.api.FieldError;
import com.findit.campus.common.error.BizException;
import com.findit.campus.pair.config.PairPolicyProperties;
import com.findit.campus.pair.domain.PairAccess;
import com.findit.campus.pair.domain.PairEnd;
import com.findit.campus.pair.domain.PairLookup;
import com.findit.campus.pair.domain.PairScore;
import com.findit.campus.pair.domain.PairStatuses;
import com.findit.campus.pair.mapper.PairScoreMapper;
import com.findit.campus.pair.web.dto.PairActionRequest;
import com.findit.campus.pair.web.dto.PairActionResponse;
import com.findit.campus.pair.web.dto.PairActionResult;

/**
 * {@code PATCH /api/pairs/{id}} —— pair 四态的唯一写入口（契约 §4.13）。
 *
 * <p><b>只认 action，不认 status</b>：{@code MATCHED} 是两端 {@code confirmed_at} 均非空的
 * 派生结果，不是客户端可写值。请求体出现 {@code status} 一律 400/10001。
 *
 * <p><b>幂等</b>：同 action 重复提交返回 200 且不写库（{@code updated_at} 不变）；
 * CONFIRM 用 {@code COALESCE} 不刷新第一次的 {@code confirmed_at}。
 * <b>不幂等的是改主意</b>：MATCHED / IGNORED 终态再改一律 409/50003，接口不提供撤销。
 *
 * <p><b>不联动</b>：不写 {@code t_record} 的任何列（status / completed_at / updated_at 都不碰），
 * 不更新其他 pair，不发通知。pair 的进展不自动把记录置为 COMPLETED —— 那是本人手动操作。
 */
@Service
public class PairActionService {

    private final PairLookupService lookupService;
    private final PairScoreMapper pairScoreMapper;
    private final PairPolicyProperties policy;

    public PairActionService(PairLookupService lookupService,
                             PairScoreMapper pairScoreMapper,
                             PairPolicyProperties policy) {
        this.lookupService = lookupService;
        this.pairScoreMapper = pairScoreMapper;
        this.policy = policy;
    }

    public PairActionResult act(long pairId, long viewerId, PairActionRequest request) {
        PairLookup lookup = lookupService.load(pairId);
        if (!lookup.pairExists() || lookup.recordsDeleted()) {
            throw BizException.of(ErrorCode.PAIR_NOT_FOUND);
        }
        PairAccess access = lookup.access(viewerId);
        if (!access.isParticipant()) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
        if (!PairAccessPolicy.isUsable(lookup.lost(), lookup.found(),
                policy.getCompletedRetentionDays(), LocalDateTime.now())) {
            throw BizException.of(ErrorCode.PAIR_INVALID);
        }
        if (lookup.pair().getScore().compareTo(policy.getContactUnlockThreshold()) < 0) {
            throw BizException.of(ErrorCode.CONTACT_TIER_INSUFFICIENT);
        }

        // 契约判定顺序 ⑤：到这一步才判 action 合法性 / 未知字段
        String unknown = request.firstUnknownField();
        if (unknown != null) {
            String reason = "status".equals(unknown)
                    ? "请求体不接受 status；MATCHED 由服务端派生"
                    : "不支持的字段：" + unknown;
            throw invalid(unknown, reason);
        }
        String action = request.getAction();
        if (action == null) {
            throw invalid("action", "action 只能是 CONTACT / CONFIRM / IGNORE");
        }
        return switch (action) {
            case "CONTACT" -> contact(lookup, access);
            case "IGNORE" -> ignore(lookup, access);
            case "CONFIRM" -> confirm(lookup, access);
            default -> throw invalid("action", "action 只能是 CONTACT / CONFIRM / IGNORE");
        };
    }

    private PairActionResult contact(PairLookup lookup, PairAccess access) {
        String status = lookup.pair().getStatus();
        if (PairStatuses.MATCHED.equals(status) || PairStatuses.IGNORED.equals(status)) {
            throw BizException.of(ErrorCode.PAIR_STATE_CONFLICT);
        }
        if (PairStatuses.NEW.equals(status)) {
            pairScoreMapper.markContacted(lookup.pair().getId());
        }
        PairScore current = reload(lookup);
        if (PairStatuses.MATCHED.equals(current.getStatus()) || PairStatuses.IGNORED.equals(current.getStatus())) {
            // 并发下被别人改到终态：CONTACT 不再是「同 action 幂等」，按终态再改处理
            throw BizException.of(ErrorCode.PAIR_STATE_CONFLICT);
        }
        return response(access, current, "已联系");
    }

    private PairActionResult ignore(PairLookup lookup, PairAccess access) {
        String status = lookup.pair().getStatus();
        if (PairStatuses.MATCHED.equals(status)) {
            throw BizException.of(ErrorCode.PAIR_STATE_CONFLICT);
        }
        if (!PairStatuses.IGNORED.equals(status)) {
            pairScoreMapper.markIgnored(lookup.pair().getId());
        }
        PairScore current = reload(lookup);
        if (PairStatuses.MATCHED.equals(current.getStatus())) {
            throw BizException.of(ErrorCode.PAIR_STATE_CONFLICT);
        }
        return response(access, current, "已忽略");
    }

    private PairActionResult confirm(PairLookup lookup, PairAccess access) {
        String status = lookup.pair().getStatus();
        if (PairStatuses.IGNORED.equals(status)) {
            throw BizException.of(ErrorCode.PAIR_STATE_CONFLICT);
        }
        if (PairStatuses.MATCHED.equals(status)) {
            // MATCHED 上的 CONFIRM 幂等：不刷新 confirmed_at
            return response(access, lookup.pair(), "已完成双方确认");
        }

        PairScore pair = lookup.pair();
        boolean needsWrite = (access.ownsLost() && pair.getLostConfirmedAt() == null)
                || (access.ownsFound() && pair.getFoundConfirmedAt() == null);
        if (needsWrite) {
            pairScoreMapper.confirmOwned(pair.getId(), LocalDateTime.now(), access.ownsLost(), access.ownsFound());
        }
        PairScore current = reload(lookup);
        if (PairStatuses.IGNORED.equals(current.getStatus())) {
            throw BizException.of(ErrorCode.PAIR_STATE_CONFLICT);
        }
        String message = PairStatuses.MATCHED.equals(current.getStatus())
                ? "已完成双方确认"
                : "已确认，等待对方确认";
        return response(access, current, message);
    }

    /** 写后回读：并发下的真实状态以库为准，而不是用本地判断「应该是什么」。 */
    private PairScore reload(PairLookup lookup) {
        PairScore current = pairScoreMapper.selectById(lookup.pair().getId());
        if (current == null) {
            throw BizException.of(ErrorCode.PAIR_NOT_FOUND);
        }
        return current;
    }

    private PairActionResult response(PairAccess access, PairScore pair, String message) {
        PairEnd myEnd = access.viewerEnd();
        PairActionResponse response = new PairActionResponse();
        response.setPairId(pair.getId());
        response.setStatus(pair.getStatus());
        response.setMatched(PairStatuses.MATCHED.equals(pair.getStatus()));
        response.setMySide(myEnd.name());
        response.setMyConfirmed((myEnd == PairEnd.LOST && pair.getLostConfirmedAt() != null)
                || (myEnd == PairEnd.FOUND && pair.getFoundConfirmedAt() != null));
        response.setOtherConfirmed((myEnd == PairEnd.FOUND && pair.getLostConfirmedAt() != null)
                || (myEnd == PairEnd.LOST && pair.getFoundConfirmedAt() != null));
        response.setLostConfirmedAt(pair.getLostConfirmedAt());
        response.setFoundConfirmedAt(pair.getFoundConfirmedAt());
        response.setUpdatedAt(pair.getUpdatedAt());
        return new PairActionResult(response, message);
    }

    private static BizException invalid(String field, String reason) {
        return BizException.of(ErrorCode.VALIDATION_FAILED, ErrorCode.VALIDATION_FAILED.message(),
                Map.of("fieldErrors", List.of(new FieldError(field, reason))));
    }
}
