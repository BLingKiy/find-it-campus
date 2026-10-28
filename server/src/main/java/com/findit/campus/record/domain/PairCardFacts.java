package com.findit.campus.record.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 一张 pair 卡片所需的全部事实（契约 §5.1），由 {@code pair} 侧的
 * {@code PairFactsAdapter} 组装、由 record 侧 DTO 直接映射。
 *
 * <p>为什么单独定义而不是把 {@code PairScore} + 两条 {@code Record} 传进来：
 * <ol>
 *   <li><b>边界</b>：卡片只需要"对方露出的那几个字段"，把两条完整 {@code Record} 传进 record
 *       的 web 层，等于把对方 {@code contact}/{@code description}/{@code publisherId} 也搬过来；
 *       这些字段一旦在手，某个后来的改动就可能顺手塞进响应。这里从类型上只给需要的字段。</li>
 *   <li><b>方向</b>：record 声明自己需要的形状，pair 实现；record 不反向依赖 pair 的实体与判定。</li>
 *   <li><b>不可变</b>：卡片是读模型，一次请求内不会被改写，避免"装配到一半被别处改了"。</li>
 * </ol>
 *
 * <p>三档 {@code tier}、{@code scorePercent}、{@code contactAvailable}/{@code contactHint}、
 * {@code mySide}/{@code myConfirmed}/{@code otherConfirmed} 等<b>派生字段已由适配器算好</b>，
 * 因为它们的口径属于 pair 上下文（{@code PairAccessPolicy}）。record 侧只负责打码与字段搬运。
 *
 * <p>{@code counterpartTitle}/{@code counterpartLocation}/{@code myNote}/{@code otherNote}
 * 是<b>原文</b>（{@code otherNote} 已按"仅对方已授权或直看档"截断），打码在 DTO 出口统一做。
 * <b>任何 {@code contact} 都不在这个对象里</b>：只有布尔 {@code contactAvailable}。
 */
public record PairCardFacts(
        long recordId,
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
        String mySide,
        boolean myUnlocked,
        boolean otherUnlocked,
        String myNote,
        String otherNote,
        long counterpartRecordId,
        String counterpartType,
        String counterpartTitle,
        String counterpartLocation,
        LocalDateTime counterpartOccurredAt,
        LocalDateTime counterpartCreatedAt,
        String counterpartStatus,
        boolean contactAvailable,
        String contactHint,
        String scoreReason) {
}
