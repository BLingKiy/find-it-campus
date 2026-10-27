package com.findit.campus.pair.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.findit.campus.pair.domain.ContactTier;
import com.findit.campus.record.domain.Record;
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
}
