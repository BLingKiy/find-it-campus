package com.findit.campus.pair.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

import org.springframework.stereotype.Service;

import com.findit.campus.common.api.ErrorCode;
import com.findit.campus.common.error.BizException;
import com.findit.campus.pair.config.PairPolicyProperties;
import com.findit.campus.pair.domain.AccessResult;
import com.findit.campus.pair.domain.ContactTier;
import com.findit.campus.pair.domain.PairAccess;
import com.findit.campus.pair.domain.PairEnd;
import com.findit.campus.pair.domain.PairLookup;
import com.findit.campus.pair.web.dto.ContactView;
import com.findit.campus.record.domain.Record;

/**
 * {@code GET /api/pairs/{id}/contact} 的判定与出口（契约 §4.14）。
 *
 * <p><b>判定顺序是契约的一部分</b>，不得调换：①pair/记录缺失 404 → ②非参与方 403/10007 →
 * ③pair 失效 403/50005 → ④分数不足 403/50002 → ⑤按档位/对方授权返回 200。
 * 顺序决定「一个非参与方能否通过错误码探测某 pair 是否存在」，所以不为了少查一次库而提前判分数。
 *
 * <p><b>只看对方那一端</b>：本端是否公开不影响本端能否看对方。分数 ≥0.90 双端直看；
 * 0.75–0.90 必须对方端 {@code unlocked_at} 非空；<0.75 一律 403。
 *
 * <p><b>每次调用都审计</b>，包括未授权与拒绝；写审计失败返回 500、不出号（fail-closed）。
 * 只有「pair 行本身不存在」不写：{@code t_access_log.pair_id} 有外键，没有可指的行。
 */
@Service
public class PairContactService {

    private final PairLookupService lookupService;
    private final PairPolicyProperties policy;
    private final AccessLogService accessLogService;

    public PairContactService(PairLookupService lookupService,
                              PairPolicyProperties policy,
                              AccessLogService accessLogService) {
        this.lookupService = lookupService;
        this.policy = policy;
        this.accessLogService = accessLogService;
    }

    public ContactView view(long pairId, long viewerId, AuditMeta meta) {
        PairLookup lookup = lookupService.load(pairId);
        if (!lookup.pairExists()) {
            // 无 pair 行 → 外键无可指目标，写不了审计；404 本身也不泄露任何东西
            throw BizException.of(ErrorCode.PAIR_NOT_FOUND);
        }
        PairAccess access = lookup.access(viewerId);

        if (lookup.recordsDeleted()) {
            audit(access, AccessResult.DENIED_PAIR_INVALID, meta);
            throw BizException.of(ErrorCode.PAIR_NOT_FOUND);
        }
        if (!access.isParticipant()) {
            audit(access, AccessResult.DENIED_NOT_OWNER, meta);
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
        if (!PairAccessPolicy.isUsable(lookup.lost(), lookup.found(),
                policy.getCompletedRetentionDays(), LocalDateTime.now())) {
            // CLOSED / COMPLETED 到期：号码已不可见，403/50005 而不是 404（契约终态口径 vF）
            audit(access, AccessResult.DENIED_PAIR_INVALID, meta);
            throw BizException.of(ErrorCode.PAIR_INVALID);
        }

        ContactTier tier = PairAccessPolicy.tier(access.pair().getScore(),
                policy.getContactDirectThreshold(), policy.getContactUnlockThreshold());
        if (tier == ContactTier.LOW) {
            audit(access, AccessResult.DENIED_LOW_SCORE, meta);
            throw BizException.of(ErrorCode.CONTACT_TIER_INSUFFICIENT);
        }

        PairEnd targetEnd = access.targetEnd();
        Record target = access.targetRecord();
        boolean targetUnlocked = access.isUnlocked(targetEnd);
        boolean numberPresent = target.getContact() != null && !target.getContact().isBlank();
        // 直看档不需要授权；可授权档必须对方已公开
        boolean visibleByTier = tier == ContactTier.DIRECT || targetUnlocked;

        if (!visibleByTier) {
            audit(access, AccessResult.DENIED_NOT_PUBLIC, meta);
            return build(access, tier, false, null, "NOT_PUBLIC", "对方尚未公开", targetUnlocked);
        }
        if (!numberPresent) {
            audit(access, AccessResult.DENIED_NOT_PROVIDED, meta);
            return build(access, tier, false, null, "NOT_PROVIDED", "对方未填写联系方式", targetUnlocked);
        }

        // 先落审计、再出号：insert 抛异常时请求以 500 结束，号码不会出现在响应里
        audit(access, AccessResult.GRANTED, meta);
        String message = tier == ContactTier.DIRECT ? "匹配度较高，可直接查看" : "对方已公开联系方式";
        return build(access, tier, true, target.getContact(), null, message, targetUnlocked);
    }

    private ContactView build(PairAccess access, ContactTier tier, boolean available, String contact,
                              String reason, String message, boolean targetUnlocked) {
        ContactView view = new ContactView();
        view.setPairId(access.pair().getId());
        view.setScore(access.pair().getScore());
        view.setScorePercent(percent(access.pair().getScore()));
        view.setTier(tier.name());
        view.setViewerSide(access.viewerEnd().name());
        view.setTargetSide(access.targetEnd().name());
        view.setAvailable(available);
        view.setContact(contact);
        view.setReason(reason);
        view.setMessage(message);
        view.setUnlockedAt(access.unlockedAt(access.targetEnd()));
        // 附言只在「能看号」且对方确实授权时返回；直看但未授权时对方没写过附言
        view.setNote(available && targetUnlocked ? access.unlockNote(access.targetEnd()) : null);
        return view;
    }

    private void audit(PairAccess access, AccessResult result, AuditMeta meta) {
        accessLogService.record(access, result, meta);
    }

    private static int percent(BigDecimal score) {
        return score == null ? 0
                : score.multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP).intValue();
    }
}
