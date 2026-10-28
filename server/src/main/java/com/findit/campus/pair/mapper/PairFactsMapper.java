package com.findit.campus.pair.mapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.findit.campus.pair.domain.PairCardRow;
import com.findit.campus.pair.domain.PairCountsRow;

/**
 * {@code t_pair_score} 的<b>只读</b>事实查询（pair 上下文所有，C1 只读）。
 *
 * <p>为什么是独立的 Mapper 而不是塞进 record 的 Mapper：表的所有权属于 pair 上下文。
 * C1 需要"这条记录有没有有效 MATCHED pair""这个查看者是不是合格对端"两类事实，
 * 但没有任何写 pair 的权限 —— 接口上只有 SELECT，写路径留给 E1。
 *
 * <p>所有时间窗口都由调用方传 {@code cutoff}（= now - retentionDays），SQL 里不出现 30 这种魔数。
 */
@Mapper
public interface PairFactsMapper {

    /** 有效 MATCHED pair 数（两端 confirmed_at 非空、两端未删未 CLOSED、未过保留期）。 */
    int countValidMatched(@Param("recordId") long recordId, @Param("cutoff") LocalDateTime cutoff);

    /**
     * 合格对端 pair 数：查看者自己的启事与目标记录配对、分数达阈值、对端 pair 不是 IGNORED。
     *
     * <p>返回 {@code > 0} 即"合格对端"。IGNORED 被 SQL 排除，因此"只有 IGNORED"与"没有 pair"
     * 都返回 0，详情出口自然同码同文案。
     */
    int countQualifyingPairs(@Param("targetRecordId") long targetRecordId,
                             @Param("viewerId") long viewerId,
                             @Param("threshold") BigDecimal threshold,
                             @Param("cutoff") LocalDateTime cutoff);

    /** 低于解锁阈值的有效 pair 数（所有者详情的 lowConfidenceCount）。 */
    int countLowConfidence(@Param("recordId") long recordId,
                           @Param("threshold") BigDecimal threshold,
                           @Param("cutoff") LocalDateTime cutoff);

    /**
     * 批量聚合（我的启事一页）：total / related / lowConfidence / matched 四个数一次算完。
     *
     * <p>「有效」= 两端（含对端）都未删除、未 CLOSED、未过保留期；一条记录自己 CLOSED 时
     * 自身端就不满足，自然得到 0 计数，不需要调用方再判断一遍。
     */
    List<PairCountsRow> countByRecordIds(@Param("ids") List<Long> ids,
                                         @Param("threshold") BigDecimal threshold,
                                         @Param("cutoff") LocalDateTime cutoff);

    /**
     * 批量取「一页记录」的 pair 卡片事实（契约 §4.2 / §4.12 / §5.1）。
     *
     * <p><b>一条 SQL 覆盖整页</b>：不是每行记录查一次。页大小 50 时那会变成 50 次往返，
     * 把列表尾延迟拖成倍数，也让 DB 连接数被一个读接口占满。
     *
     * <p>入参 {@code ids} 是本人记录 id 集合；返回行里的 {@code recordId} 是这张卡片挂在哪个
     * {@code ids} 元素下（同一条 pair 两端同主时会出现两行，{@code mySide} 不同），
     * 调用方按它分组即可，不需要再判端。
     *
     * <p>过滤全部在 SQL：{@code score >= threshold}（低分只进 lowConfidenceCount，不出卡片）、
     * 两端未删除、两端非 CLOSED、COMPLETED 端未过保留期。排序也在这里定死
     * （{@code score DESC, pair_id ASC}）—— 契约要求"按匹配度从高到低"由服务端定序，前端不再排。
     */
    List<PairCardRow> selectCardsByRecordIds(@Param("ids") List<Long> ids,
                                             @Param("threshold") BigDecimal threshold,
                                             @Param("cutoff") LocalDateTime cutoff);
}
