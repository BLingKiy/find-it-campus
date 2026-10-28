package com.findit.campus.pair.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.findit.campus.common.api.ErrorCode;
import com.findit.campus.common.api.FieldError;
import com.findit.campus.common.error.BizException;
import com.findit.campus.common.masking.MaskingPolicy;
import com.findit.campus.notification.domain.Notification;
import com.findit.campus.notification.mapper.NotificationMapper;
import com.findit.campus.pair.config.PairPolicyProperties;
import com.findit.campus.pair.domain.PairAccess;
import com.findit.campus.pair.domain.PairEnd;
import com.findit.campus.pair.domain.PairLookup;
import com.findit.campus.pair.domain.PairScore;
import com.findit.campus.pair.mapper.PairScoreMapper;
import com.findit.campus.pair.web.dto.ContactUnlockRequest;
import com.findit.campus.pair.web.dto.ContactUnlockResponse;
import com.findit.campus.record.domain.Record;

/**
 * {@code POST /api/pairs/{id}/contact-unlock} —— 本端授权公开 / 撤回（契约 §4.15）。
 *
 * <p><b>授权是逐端的</b>：只写当前用户所在端（双端同主时固定 LOST，与 §4.14 一致），
 * 对方的授权状态与附言一字不动。A、B 两条 pair 也互不影响。
 *
 * <p><b>撤回清空附言</b>：任务书口径（人类 2026-10-27 拍板）要求撤回后附言同时清空，
 * 与契约 §4.15 正文的字段语义一致；实现落点是 {@code revokeLost/revokeFound} 一条 UPDATE
 * 同时置空两列，不留「已撤回但附言还在」的中间态。
 *
 * <p><b>REVOKE 不检查分数</b>：分数掉到 0.75 以下也必须允许撤回同意；
 * 但 pair 已失效（CLOSED/到期）时仍返回 403/50005 —— 此时对端本来就看不到号，撤回不再改变可见性。
 *
 * <p>PUBLISH 成功后给对端写一条 {@code PAIR_UNLOCK} 通知（唯一键幂等），正文不含号码。
 * 授权与通知在同一事务里：通知写失败则授权一起回滚，避免「已公开但对方没收到」。
 */
@Service
public class PairUnlockService {

    private static final int NOTE_MAX_CODE_POINTS = 200;

    private final PairLookupService lookupService;
    private final PairScoreMapper pairScoreMapper;
    private final NotificationMapper notificationMapper;
    private final PairPolicyProperties policy;

    public PairUnlockService(PairLookupService lookupService,
                             PairScoreMapper pairScoreMapper,
                             NotificationMapper notificationMapper,
                             PairPolicyProperties policy) {
        this.lookupService = lookupService;
        this.pairScoreMapper = pairScoreMapper;
        this.notificationMapper = notificationMapper;
        this.policy = policy;
    }

    @Transactional
    public ContactUnlockResponse unlock(long pairId, long viewerId, ContactUnlockRequest request) {
        boolean revoke = resolveAction(request.getAction());
        String maskedNote = revoke ? null : maskNote(request.getNote());

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

        PairEnd side = access.viewerEnd();
        if (revoke) {
            if (side == PairEnd.LOST) {
                pairScoreMapper.revokeLost(pairId);
            } else {
                pairScoreMapper.revokeFound(pairId);
            }
        } else {
            if (access.pair().getScore()
                    .compareTo(policy.forEngine(access.pair().getEngine()).unlock()) < 0) {
                throw BizException.of(ErrorCode.CONTACT_TIER_INSUFFICIENT);
            }
            LocalDateTime now = LocalDateTime.now();
            if (side == PairEnd.LOST) {
                pairScoreMapper.publishLost(pairId, now, maskedNote);
            } else {
                pairScoreMapper.publishFound(pairId, now, maskedNote);
            }
            notifyCounterpart(access, side);
        }

        PairScore refreshed = pairScoreMapper.selectById(pairId);
        return response(pairId, side, !revoke, refreshed == null ? access.pair() : refreshed);
    }

    private ContactUnlockResponse response(long pairId, PairEnd side, boolean published, PairScore pair) {
        ContactUnlockResponse response = new ContactUnlockResponse();
        response.setPairId(pairId);
        response.setSide(side.name());
        response.setPublic(published);
        response.setUnlockedAt(published
                ? (side == PairEnd.LOST ? pair.getLostUnlockedAt() : pair.getFoundUnlockedAt())
                : null);
        response.setNote(published
                ? (side == PairEnd.LOST ? pair.getLostUnlockNote() : pair.getFoundUnlockNote())
                : null);
        return response;
    }

    private void notifyCounterpart(PairAccess access, PairEnd side) {
        Record target = access.record(side.opposite());
        Long recipient = target.getPublisherId();
        // 双端同主时不给自己发通知：通知是「对方做了动作」的提示，自己知道自己刚点了什么
        if (recipient == null || recipient == access.viewerId()) {
            return;
        }
        Notification notification = new Notification();
        notification.setUserId(recipient);
        notification.setPairId(access.pair().getId());
        notification.setType("PAIR_UNLOCK");
        notification.setTitle(MaskingPolicy.maskDescription("对方已公开联系方式"));
        notification.setBody(MaskingPolicy.maskDescription(
                "配对 #" + access.pair().getId() + " 的对方已公开联系方式，点击查看"));
        notificationMapper.insertIfAbsent(notification);
    }

    /** @return true = REVOKE，false = PUBLISH */
    private static boolean resolveAction(String action) {
        if (action == null || action.isEmpty()) {
            // 省略 action 按 PUBLISH 处理（兼容只发 {note} 的调用）
            return false;
        }
        if ("PUBLISH".equals(action)) {
            return false;
        }
        if ("REVOKE".equals(action)) {
            return true;
        }
        throw invalid("action", "action 只能是 PUBLISH 或 REVOKE");
    }

    /**
     * 附言校验 + 打码。
     *
     * <p>长度按 Unicode 码点算 200「字」；先校验原文、再打码。打码可能让文本变长
     * （例如号码被替换成固定串），因此对打码结果再做一次 200 码点的防御性截断，
     * 保证不会因为替换反而撞上 {@code VARCHAR(200)} 把一次授权变成 500。
     */
    private static String maskNote(String raw) {
        if (raw == null) {
            return null;
        }
        String note = raw.strip();
        if (note.isEmpty()) {
            return null;
        }
        if (note.codePointCount(0, note.length()) > NOTE_MAX_CODE_POINTS) {
            throw invalid("note", "长度需在 0 到 200 之间");
        }
        String masked = MaskingPolicy.maskDescription(note);
        int[] codePoints = masked.codePoints().toArray();
        if (codePoints.length > NOTE_MAX_CODE_POINTS) {
            return new String(codePoints, 0, NOTE_MAX_CODE_POINTS);
        }
        return masked;
    }

    private static BizException invalid(String field, String reason) {
        return BizException.of(ErrorCode.VALIDATION_FAILED, ErrorCode.VALIDATION_FAILED.message(),
                Map.of("fieldErrors", List.of(new FieldError(field, reason))));
    }
}
