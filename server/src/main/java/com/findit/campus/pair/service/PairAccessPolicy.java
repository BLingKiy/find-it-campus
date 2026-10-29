package com.findit.campus.pair.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

import com.findit.campus.pair.domain.ContactTier;
import com.findit.campus.record.domain.Record;
import com.findit.campus.record.domain.RecordReviewState;
import com.findit.campus.record.domain.RecordStatus;
import com.findit.campus.record.service.RecordVisibilityPolicy;

/**
 * pair 访问的<b>纯函数</b>判定：可用性、三档、逐端授权。
 *
 * <p>为什么不把这些 if 散在三个 service 里：contact / contact-unlock / PATCH pair 用的是
 * <b>同一套</b>「pair 还能不能用」与「分数到哪一档」判定，三处各写一遍，改口径时漏一处就是
 * 「取号 403 但还能确认」这种自相矛盾。纯函数没有 IO，能用单测穷举边界。
 *
 * <p>保留期判定复用 {@link RecordVisibilityPolicy#isWithinRetention}：COMPLETED 的读时窗口
 * 只有一份实现，不在 pair 上下文里再写一次 {@code plusDays}。
 */
public final class PairAccessPolicy {

    private PairAccessPolicy() {
    }

    /**
     * pair 是否仍可用：两端都未删除、都未 CLOSED、COMPLETED 端都未过保留期。
     *
     * <p>删除由 {@link PairLookupService} 在读记录时按 {@code deleted_at IS NULL} 处理，
     * 这里只判状态；传 null 一律不可用（缺一端就没有 pair 可言）。
     */
    public static boolean isUsable(Record lost, Record found, int retentionDays, LocalDateTime now) {
        return isRecordUsable(lost, retentionDays, now) && isRecordUsable(found, retentionDays, now);
    }

    private static boolean isRecordUsable(Record record, int retentionDays, LocalDateTime now) {
        if (record == null || record.getStatus() == null) {
            return false;
        }
        // 发布闸门：任一端的展示文本没通过审核，这一对就不能被取号/确认/忽略。
        // SQL 读路径已按 is_visible 过滤；这里是纯函数层的第二道，防未来新入口绕过 SQL。
        if (record.getReviewState() != RecordReviewState.APPROVED) {
            return false;
        }
        if (record.getStatus() == RecordStatus.CLOSED) {
            return false;
        }
        if (record.getStatus() == RecordStatus.COMPLETED) {
            return RecordVisibilityPolicy.isWithinRetention(record.getCompletedAt(), retentionDays, now);
        }
        return true;
    }

    /**
     * 三档判定：先看直看档，再看可授权档下界，其余是 LOW。
     *
     * <p>用 {@link BigDecimal#compareTo} 而不是 {@code equals}：{@code 0.90} 与 {@code 0.9000}
     * 在 scale 上不相等、在数值上相等，阈值比较只能按数值。
     */
    public static ContactTier tier(BigDecimal score, BigDecimal directThreshold, BigDecimal unlockThreshold) {
        if (score == null) {
            return ContactTier.LOW;
        }
        if (score.compareTo(directThreshold) >= 0) {
            return ContactTier.DIRECT;
        }
        if (score.compareTo(unlockThreshold) >= 0) {
            return ContactTier.AUTHORIZABLE;
        }
        return ContactTier.LOW;
    }

    /**
     * 契约 §5.1 的 {@code contactAvailable} 公式，<b>唯一实现</b>。
     *
     * <p>{@code (tier=DIRECT 或 otherUnlocked) 且对方 contact 非空}。注意这里只回答
     * 「按钮能不能点」，号码本身不在这里出现 —— {@code PairCard} 永远拿不到 contact。
     *
     * <p>额外排除 {@link ContactTier#LOW}：卡片查询本身已过滤 {@code score >= contact_unlock_threshold}，
     * 所以这是<b>不可达分支的防御</b>。为什么仍然要写：如果以后有人放宽 SQL，LOW 对即使对方
     * 已授权取号也会被 §4.14 拒绝（403/50002），此时把按钮点成"可看"就是骗用户点一次 403。
     */
    public static boolean contactAvailable(ContactTier tier, boolean otherUnlocked, boolean contactPresent) {
        return tier != ContactTier.LOW && (tier == ContactTier.DIRECT || otherUnlocked) && contactPresent;
    }

    /**
     * 契约 §5.1 的 {@code contactHint} 四选一，<b>唯一实现</b>（§4.14 的 message 也用它）。
     *
     * <p>判定顺序决定文案：先看「能不能看」（档位/授权），再看「有没有号」，最后才分直看与授权。
     * 直看档但对方没填号时给「对方未填写联系方式」而不是「匹配度较高」——
     * 否则按钮会显示可点、点开却什么都拿不到。
     */
    public static String contactHint(ContactTier tier, boolean otherUnlocked, boolean contactPresent) {
        if (tier == ContactTier.LOW || (tier != ContactTier.DIRECT && !otherUnlocked)) {
            return "对方尚未公开";
        }
        if (!contactPresent) {
            return "对方未填写联系方式";
        }
        return tier == ContactTier.DIRECT ? "匹配度较高，可直接查看" : "对方已公开联系方式";
    }

    /**
     * {@code scorePercent = round(score*100)}（契约 §5.1：后端算好，前端不自己乘）。
     *
     * <p>{@code HALF_UP} 与 {@code PairContactService} 原实现一致；{@code null} 记 0。
     */
    public static int scorePercent(BigDecimal score) {
        return score == null ? 0
                : score.multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP).intValue();
    }
}
