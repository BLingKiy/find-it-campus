package com.findit.campus.support;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.findit.campus.record.domain.PairCardFacts;

/**
 * 卡片事实的测试夹具：只保留三张"典型卡"（直看档 / 可授权未公开 / 含敏感文本）。
 *
 * <p>为什么不用 builder：{@link PairCardFacts} 有 27 个分量，builder 会让"默认值是什么"变得不可见；
 * 三个具名夹具把"这张卡代表哪个业务场景"写进方法名，测试读起来不用追参数。
 */
public final class PairCardFixtures {

    private PairCardFixtures() {
    }

    /** 0.92 直看档：两端已确认（MATCHED），对方已填号。 */
    public static PairCardFacts direct(long recordId, long pairId) {
        return facts(recordId, pairId, new BigDecimal("0.9200"), 92, "DIRECT", "MATCHED",
                true, true, true,
                LocalDateTime.of(2026, 10, 27, 9, 0), LocalDateTime.of(2026, 10, 27, 9, 10),
                "DEEPSEEK", false,
                false, false, null, null,
                "图书馆拾到黑色钱包", "图书馆三楼自习区",
                true, "匹配度较高，可直接查看");
    }

    /** 0.80 可授权档、对方未公开：卡片在，但按钮不可点。 */
    public static PairCardFacts authorizableNotPublic(long recordId, long pairId) {
        return facts(recordId, pairId, new BigDecimal("0.8000"), 80, "AUTHORIZABLE", "NEW",
                false, false, false, null, null,
                "LOCAL_KEYWORD", true,
                false, false, null, null,
                "图书馆拾到蓝色水杯", "图书馆二楼", false, "对方尚未公开");
    }

    /** 含号码的文本：用于证明出口打码、且响应里没有 contact 键。 */
    public static PairCardFacts sensitive(long recordId, long pairId) {
        return facts(recordId, pairId, new BigDecimal("0.9500"), 95, "DIRECT", "MATCHED",
                true, true, true,
                LocalDateTime.of(2026, 10, 27, 9, 0), LocalDateTime.of(2026, 10, 27, 9, 10),
                "DEEPSEEK", false,
                true, true,
                "周六图书馆见 13800000027", "我在前台 13900000028",
                "图书馆捡到钱包 13800000029", "第三教学楼201",
                true, "匹配度较高，可直接查看");
    }

    private static PairCardFacts facts(long recordId,
                                       long pairId,
                                       BigDecimal score,
                                       int scorePercent,
                                       String tier,
                                       String status,
                                       boolean matched,
                                       boolean myConfirmed,
                                       boolean otherConfirmed,
                                       LocalDateTime lostConfirmedAt,
                                       LocalDateTime foundConfirmedAt,
                                       String engine,
                                       boolean degraded,
                                       boolean myUnlocked,
                                       boolean otherUnlocked,
                                       String myNote,
                                       String otherNote,
                                       String counterpartTitle,
                                       String counterpartLocation,
                                       boolean contactAvailable,
                                       String contactHint) {
        return new PairCardFacts(
                recordId, pairId, score, scorePercent, tier, status, matched,
                myConfirmed, otherConfirmed, lostConfirmedAt, foundConfirmedAt,
                engine, degraded, "LOST", myUnlocked, otherUnlocked, myNote, otherNote,
                recordId + 100, "FOUND", counterpartTitle, counterpartLocation,
                LocalDateTime.of(2026, 10, 20, 15, 0), LocalDateTime.of(2026, 10, 20, 15, 30),
                "PENDING", contactAvailable, contactHint, null);
    }
}
