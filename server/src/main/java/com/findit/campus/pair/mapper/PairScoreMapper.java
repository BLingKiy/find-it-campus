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
 * <p>写路径按「谁的动作」分成两组，这是刻意的：
 * <ul>
 *   <li><b>打分链路</b>（D1）：只有 {@link #upsertScore}。更新列清单写在 XML 的
 *       {@code ON DUPLICATE KEY UPDATE} 里，且<b>不包含</b>
 *       {@code status / *_unlocked_at / *_unlock_note / *_confirmed_at / created_at}；
 *       {@code PairScoreUpsertSqlTest} 直接解析 XML 断言这一点。</li>
 *   <li><b>用户动作</b>（E1）：{@code markContacted / markIgnored / confirmOwned /
 *       publishLost / publishFound / revokeLost / revokeFound}。这些方法<b>只被
 *       PairActionService / PairUnlockService 调用</b>，D1 的打分链路拿不到它们，
 *       所以「重算把 IGNORED 翻回 NEW」在类型层面就不成立。</li>
 * </ul>
 */
@Mapper
public interface PairScoreMapper {

    /** 按唯一键取一行；不存在返回 {@code null}。 */
    PairScore selectByPair(@Param("lostId") long lostId, @Param("foundId") long foundId);

    /** 按主键取一行（E1 三个 pair 接口的入口）；不存在返回 {@code null}。 */
    PairScore selectById(@Param("id") long id);

    /** NEW → CONTACTED；返回 0 = 并发中已被改成其它状态，调用方回读再判定。 */
    int markContacted(@Param("id") long id);

    /** NEW / CONTACTED → IGNORED；返回 0 = 已处于 MATCHED/IGNORED，调用方回读再判定。 */
    int markIgnored(@Param("id") long id);

    /**
     * 把调用者拥有的端写 {@code confirmed_at}，并由两端是否都非空派生 status。
     *
     * <p>一条 UPDATE 完成「写确认时间 + 可能置 MATCHED」，因为 {@code ck_pair_matched} 要求
     * {@code MATCHED ⇔ 两端 confirmed_at 均非空}：先写第二端再写 status 会被 CHECK 拒绝，
     * 而先写 status 再写确认时间同样非法。SQL 里 status 赋值放在两列之后，依赖 MySQL
     * 「SET 子句从左到右求值、后面的表达式能看到前面刚写的值」的语义（8.0 文档明确）。
     *
     * @param ownsLost  调用者是否拥有 LOST 端（只写自己拥有的端，不替对方确认）
     * @param ownsFound 调用者是否拥有 FOUND 端
     */
    int confirmOwned(@Param("id") long id,
                     @Param("at") LocalDateTime at,
                     @Param("ownsLost") boolean ownsLost,
                     @Param("ownsFound") boolean ownsFound);

    /** 本端授权公开：已公开则保持原时间（COALESCE），附言覆盖为本次打码后的值。 */
    int publishLost(@Param("id") long id, @Param("at") LocalDateTime at, @Param("note") String note);

    /** 同上，FOUND 端。 */
    int publishFound(@Param("id") long id, @Param("at") LocalDateTime at, @Param("note") String note);

    /** 撤回本端授权并清空附言（任务书口径：撤回后附言不保留）。 */
    int revokeLost(@Param("id") long id);

    /** 同上，FOUND 端。 */
    int revokeFound(@Param("id") long id);

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
