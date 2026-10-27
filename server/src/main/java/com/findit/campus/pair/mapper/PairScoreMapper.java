package com.findit.campus.pair.mapper;

import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.findit.campus.pair.domain.PairScore;

/**
 * {@code t_pair_score} 的写路径 + D1 需要的读（pair 上下文所有）。
 *
 * <p>与 {@link PairFactsMapper}（C1 的只读事实查询）分文件：那个接口的读者是 record 上下文，
 * 这个接口的读者只有打分链路。两个接口各自只暴露"读者需要的最小集合"：
 * record 侧<b>拿不到</b>写 pair 的方法，打分链路也<b>不需要</b>聚合计数。
 *
 * <p>写路径只有一条 {@link #upsertScore}，这是刻意的：
 * <ul>
 *   <li>更新列清单写在 XML 的 {@code ON DUPLICATE KEY UPDATE} 里，且<b>不包含</b>
 *       {@code status / *_unlocked_at / *_unlock_note / *_confirmed_at / created_at}；
 *       {@code PairScoreUpsertSqlTest} 直接解析 XML 断言这一点。</li>
 *   <li>没有 {@code updateStatus} 之类的方法：pair 状态只能由用户动作（E1）改，
 *       打分链路一旦有能力写 status，"重算把 IGNORED 翻回 NEW"就只是一个 if 的距离。</li>
 * </ul>
 */
@Mapper
public interface PairScoreMapper {

    /** 按唯一键取一行；不存在返回 {@code null}。 */
    PairScore selectByPair(@Param("lostId") long lostId, @Param("foundId") long foundId);

    /**
     * 写入/更新一行的分数类列（唯一键 {@code (lost_id, found_id)}）。
     *
     * <p>返回影响行数：MySQL 的 {@code ON DUPLICATE KEY UPDATE} 在"值没变"时返回 0。
     * 调用方<b>不能</b>把 0 当失败（它不是错误，只是"没有列发生变化"），只需要它来打日志。
     */
    int upsertScore(PairScore pairScore);

    /**
     * 到期重算候选：{@code engine=LOCAL_KEYWORD} 且 {@code updated_at < updatedBefore}。
     *
     * <p>为什么要冷却：key 恢复后立刻全量重算会把一次扫描变成几千次 LLM 调用；
     * 而"降级了但 key 一直没恢复"的情况下，每轮都重算等于每天烧一遍额度。
     * 冷却窗口（默认 60 分钟）把重算频率钉在"每小时最多一轮"。
     */
    List<PairScore> selectDegradedPairs(@Param("updatedBefore") LocalDateTime updatedBefore,
                                        @Param("limit") int limit);

    /**
     * 某条记录参与的 pair 里还有多少条是 {@code LOCAL_KEYWORD}（决定 {@code pair_state=DEGRADED}）。
     *
     * <p>为什么不在内存里数本轮结果：本轮可能全部跳过（哈希未变），此时"看起来一条降级都没有"，
     * 而库里明明躺着上次的降级行 —— 那会让 {@code pair_state} 从 DEGRADED 翻回 READY，
     * 前端于是不再提示"已降级"，用户就会以为分数是智能匹配的。以库为准，避免这个错误。
     */
    int countDegradedByRecord(@Param("recordId") long recordId);

    /**
     * 某条记录参与的所有 pair（两端任一命中），按 {@code updated_at} 升序取前 N 条。
     *
     * <p>用途：重算时把"已存在但本次没被召回进 topN"的对端也带上，避免老 pair 的 hash
     * 永远停在旧内容上（详见 {@code PairScoringService#withExistingCounterparts}）。
     * 升序保证被反复编辑的记录每次优先处理最旧的那些，长期看不会饿死任何一条。
     */
    List<PairScore> selectByRecord(@Param("recordId") long recordId, @Param("limit") int limit);
}
