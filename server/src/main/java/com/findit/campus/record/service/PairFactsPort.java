package com.findit.campus.record.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import com.findit.campus.record.domain.MyPairCounts;
import com.findit.campus.record.domain.PairCardFacts;

/**
 * record 上下文对 pair 事实的<b>只读</b>依赖（依赖倒置：接口在 record，实现在 pair）。
 *
 * <p>为什么不让 record 直接查 {@code t_pair_score}：那张表的所有权属于 pair 上下文（D1/E1）。
 * 两边都写同一张表，改列时就会"谁先改谁背锅"。这里只暴露 record 真正需要的四个问题，
 * 由 {@code pair} 模块的适配器实现；D1 扩展 pair 模块时，只需让适配器继续满足这个接口。
 *
 * <p>时间参数统一传 {@code completedCutoff}（= now - retentionDays），而不是让适配器自己读配置：
 * 保留期是<b>同一个配置实例</b>算出来的，传进来才能保证详情判定、my 计数、pair 有效性
 * 三处用的是同一个窗口边界（A1 §7.1「不散落硬编码」）。
 *
 * <p>这些方法都是读：不写 pair 状态、不写审计。C1 的写路径（记录状态）只读 pair 事实。
 */
public interface PairFactsPort {

    /**
     * 有效 MATCHED pair 数：两端 {@code confirmed_at} 均非空、两端记录未删除且未 CLOSED、
     * 未过保留期（COMPLETED 端只看窗口）。
     *
     * @param recordId        目标记录（任一端命中即可）
     * @param completedCutoff 保留期下界；{@code completed_at} 必须晚于它
     */
    int countValidMatched(long recordId, LocalDateTime completedCutoff);

    /**
     * 查看者是否是目标记录（COMPLETED）的「合格对端」。
     *
     * <p>判定（A1 §9.3 / 补充 #2）：查看者本人某条未删除、未 CLOSED、未过期的启事与目标记录存在 pair，
     * {@code score ≥ completedVisibleThreshold}，且 {@code pair.status ≠ 'IGNORED'}。
     * IGNORED 的 pair 与「没有 pair」同码同文案（404）。
     */
    boolean viewerHasQualifyingPair(long targetRecordId, long viewerId, BigDecimal scoreThreshold,
                                    LocalDateTime completedCutoff);

    /**
     * 目标记录下低于解锁阈值的 pair 数（所有者详情页的 {@code lowConfidenceCount}）。
     *
     * <p>只看有效 pair（对端未删/未 CLOSED/未过期）；不含等于阈值的（等于即进卡片）。
     */
    int countLowConfidence(long recordId, BigDecimal unlockThreshold, LocalDateTime completedCutoff);

    /**
     * 批量版：给「我的启事」一页记录算四个计数，避免逐行查询（N+1）。
     *
     * @return 以 recordId 为键；没有有效 pair 的记录不会出现在 map 里，调用方用 {@link MyPairCounts#EMPTY} 兜底
     */
    Map<Long, MyPairCounts> countsOf(Collection<Long> recordIds, BigDecimal unlockThreshold,
                                     LocalDateTime completedCutoff);

    /**
     * 批量版：给「详情 / 我的启事」一页记录装配 {@code pairs[]}（契约 §4.2 / §4.12 / §5.1）。
     *
     * <p><b>只返回能出卡片的 pair</b>：{@code score ≥ unlockThreshold} 且 {@code pairUsable}
     * （两端未删除、非 CLOSED、COMPLETED 端未过保留期）。低分只进
     * {@link #countLowConfidence}；失效 pair 一律不出现在返回值里。
     *
     * <p><b>一次批量取回</b>：查询条数与 {@code recordIds} 的元素个数无关 —— 这是本方法存在的
     * 唯一理由。逐条记录查会让一页 50 行变成 50 次往返。
     *
     * <p>返回的 {@code List} 已按契约定序（{@code score} 降序，同分 {@code pairId} 升序），
     * 调用方不得再排；没有有效卡片的记录不会出现在 map 里（用空列表兜底）。
     *
     * @param unlockThreshold {@code contact_unlock_threshold}（卡片下界）
     * @param directThreshold {@code contact_direct_threshold}（三档上界，用于 {@code tier}）
     * @param completedCutoff 保留期下界（= now - retentionDays），与计数、详情判定同一个窗口
     */
    Map<Long, List<PairCardFacts>> cardsOf(Collection<Long> recordIds,
                                           BigDecimal unlockThreshold,
                                           BigDecimal directThreshold,
                                           LocalDateTime completedCutoff);
}
