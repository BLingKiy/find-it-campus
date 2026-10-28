package com.findit.campus.record.mapper;

import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.findit.campus.record.domain.MyRecordRow;
import com.findit.campus.record.domain.Record;
import com.findit.campus.record.domain.RecordCandidate;
import com.findit.campus.record.domain.RecordSummary;
import com.findit.campus.record.domain.RecordType;

/**
 * {@code t_record} 的数据访问出口（原生 MyBatis + XML，ADR-004）。
 *
 * <p>贯穿所有查询的不变量，写在接口上是因为<b>忘带就是 bug</b>：
 * <ol>
 *   <li>读路径一律带 {@code deleted_at IS NULL}（ADR-006：逻辑删除对一切读接口不可见）</li>
 *   <li>写路径用"影响行数"表达命中与否：改到 0 行 ⇒ 记录不存在/已删/状态已被别人改过，
 *       比"先 SELECT 再 UPDATE"少一次往返，也少一个 TOCTOU 窗口</li>
 * </ol>
 *
 * <p><b>{@code t_record.status} 的写路径只有两条</b>（架构 §9.2）：{@code POST} 插入时初始化
 * {@code PENDING}，以及 {@link #markCompleted}/{@link #close}（所有者 PATCH）。
 * {@code updateContent} 不接收 status，{@code markDeleted} 只写 {@code deleted_at}，
 * 任何系统任务都没有写 status 的 SQL —— 这是「任务跑一轮 status/updated_at 不变」的结构性保证。
 *
 * <p>{@code V2-C1 起枚举与 DDL 对齐}：{@code CLAIMED} 删除，三态为 {@code PENDING/COMPLETED/CLOSED}。
 */
@Mapper
public interface RecordMapper {

    /** 公共列表总数（与 {@link #selectSummaries} 用<b>同一个</b>条件对象，避免两处条件写歪）。 */
    long countByCondition(@Param("condition") RecordCondition condition);

    /** 公共列表当前页。可见集合恒为 {@code PENDING}，由 SQL 硬约束，不由调用方传值。 */
    List<RecordSummary> selectSummaries(@Param("condition") RecordCondition condition,
                                        @Param("offset") int offset,
                                        @Param("limit") int limit);

    /** 我的启事总数（publisher_id 在 SQL 里强绑，不信任调用方传的类型/状态之外的维度）。 */
    long countByPublisher(@Param("condition") MyRecordCondition condition);

    /** 我的启事当前页；三态都属于本人，因此不强制 PENDING。 */
    List<MyRecordRow> selectMyRecords(@Param("condition") MyRecordCondition condition,
                                      @Param("offset") int offset,
                                      @Param("limit") int limit);

    /** 详情（含分类名与所有权/终态所需列）；已逻辑删除的返回 {@code null}。 */
    Record selectById(@Param("id") long id);

    /**
     * 并发冲突后的状态回读（P2-1）：{@code SELECT ... FOR UPDATE}，读最新已提交行。
     *
     * <p>为什么不用 {@link #selectById}：后者是普通一致性读，MySQL REPEATABLE READ 下沿用事务
     * 起点的快照，可能仍返回 PENDING，导致"已被并发 PATCH 关闭"被误判成未知冲突。
     *
     * @return 当前 status；记录不存在或已逻辑删除时为 {@code null}
     */
    String selectStatusByIdForUpdate(@Param("id") long id);

    /** 新增；自增主键回填到 {@link Record#getId()}。status 固定 PENDING，completed_at 不写（默认 NULL）。 */
    int insert(Record record);

    /** 改内容（不含 status）；返回影响行数，0 = 不存在/已删/已被并发改成终态。 */
    int updateContent(Record record);

    /**
     * 标记完成：<b>同一条 UPDATE</b> 写 status/completed_at/updated_at。
     *
     * <p>为什么必须一条语句：分两步写会在中间崩溃时留下 {@code COMPLETED + completed_at NULL}
     * 的脏行，而 {@code ck_record_completed_at} 会直接拒绝它 —— 等于把一次写放大成一次故障。
     * {@code WHERE status='PENDING'} 同时兜住并发双击：影响 0 行 = 已经被别人改过。
     */
    int markCompleted(@Param("id") long id, @Param("at") LocalDateTime at);

    /** 关闭：无前置条件；{@code completed_at} 显式置 NULL（PENDING 本来就是 NULL，兜住脏数据）。 */
    int close(@Param("id") long id, @Param("at") LocalDateTime at);

    /** 逻辑删除；只写 {@code deleted_at}/{@code updated_at}，<b>不写 status</b>（契约 §4.6）。 */
    int markDeleted(@Param("id") long id, @Param("deletedAt") LocalDateTime deletedAt);

    /**
     * 匹配候选池（只读，{@code MATCHABLE}）：{@code PENDING} + 保留期内的 {@code COMPLETED}，
     * 排除 {@code CLOSED}。口径与 {@link #selectSummariesByIds} 完全一致（同一份 SQL 片段）。
     *
     * @param completedCutoff 保留期下界（= now - retentionDays），由配置算出
     */
    List<RecordCandidate> selectCandidatePool(@Param("type") RecordType type,
                                              @Param("categoryId") Long categoryId,
                                              @Param("completedCutoff") LocalDateTime completedCutoff,
                                              @Param("limit") int limit);

    /**
     * 按 id 批量取卡片（{@code /api/match} 回表补展示字段 + 过滤非法候选，ADR-008）。
     *
     * <p>这里再校验一次 {@code MATCHABLE} 不是冗余：候选池装载与结果回表之间可能已经过了几十毫秒，
     * 期间记录可能被删除、关闭或刚过保留期。过滤放在 SQL 里，service 只按 id 对号入座。
     */
    List<RecordSummary> selectSummariesByIds(@Param("ids") List<Long> ids,
                                             @Param("completedCutoff") LocalDateTime completedCutoff);

    /**
     * 按 id 批量取完整记录（D1 打分需要 description/categoryName/publisherId，候选池读模型不带这些）。
     *
     * <p>不做 {@code MATCHABLE} 过滤：这是"按 id 取事实"，过滤是调用方的事 —— 打分链路要能读到
     * 一条刚被关闭/刚过期的记录，才能判断"这一对已经失效，跳过"，而不是把它当不存在。
     * 已逻辑删除的仍然不返回（{@code deleted_at IS NULL}）。
     */
    List<Record> selectByIds(@Param("ids") List<Long> ids);

    // ------------------------------------------------------------------
    // pair_state：系统内部状态（不是业务状态），只有 D1 的打分链路写
    // ------------------------------------------------------------------

    /**
     * 进入打分：{@code pair_state='SCORING'}。
     *
     * <p><b>不写 {@code updated_at}</b>：架构 §5.4 / DDL 注释要求"系统任务跑完 updated_at 不变"，
     * 这是「谁改了记录」可审计的结构性保证。{@code t_record.updated_at} 刻意没有
     * {@code ON UPDATE CURRENT_TIMESTAMP}，就是为了让这条 UPDATE 能不改它。
     */
    int markPairScoring(@Param("id") long id);

    /**
     * 打分结束：{@code pair_state} = READY / DEGRADED，{@code pair_scored_at=NOW()}。
     *
     * <p>同上，不写 {@code updated_at}/{@code status}/{@code completed_at}。
     *
     * @param state {@code READY}（全部 pair 由 LLM 打分）或 {@code DEGRADED}（至少一条降级）
     */
    int markPairScored(@Param("id") long id, @Param("state") String state, @Param("at") LocalDateTime at);

    /**
     * 每日扫描的记录候选（架构 §5.4 ①）：{@code PENDING} 或保留期内的 {@code COMPLETED}，
     * 且 {@code pair_state IN ('PENDING','SCORING')} 停在超过 stale 窗口，或 {@code pair_state='DEGRADED'}。
     *
     * <p>排除 {@code CLOSED}、已删除与<b>过期 {@code COMPLETED}</b>（P2-4）：它们不召回，
     * 重算只会花 LLM 额度并产生指向 404 的 pair/通知。保留期口径与
     * {@code RecordVisibilityPolicy#isMatchable} 一致。
     *
     * @param staleBefore     卡在 SCORING 的过期下界（= now - staleMinutes）
     * @param completedCutoff 保留期下界（= now - retentionDays）
     */
    List<Long> selectPairScanTargets(@Param("staleBefore") LocalDateTime staleBefore,
                                     @Param("completedCutoff") LocalDateTime completedCutoff,
                                     @Param("limit") int limit);

    /**
     * 到期 COMPLETED 计数（架构 §5.4 的"到期清扫"）：<b>只读</b>。
     *
     * <p>到期不改 {@code status}、不改 {@code completed_at}、不删记录 —— 可见性是读时谓词，
     * 定时器只负责把它们<b>数出来</b>写进日志，用于容量与合规统计。返回 0 也是正常结果。
     */
    long countExpiredCompleted(@Param("cutoff") LocalDateTime cutoff);

    // ------------------------------------------------------------------
    // 发布闸门（ZHYYKFX-85）：审核态写路径。全部带 review_state='PENDING' 条件。
    // ------------------------------------------------------------------

    /** 记录一次 AI 尝试次数；{@code WHERE review_state='PENDING'} 保证不覆盖已定稿结论。 */
    int markReviewAttempt(@Param("id") long id, @Param("attempts") int attempts);

    /** AI/人工通过：同一条 UPDATE 写展示文本、摘要与全部审计元数据，避免半套状态。 */
    int approveReview(@Param("id") long id,
                      @Param("title") String title,
                      @Param("description") String description,
                      @Param("location") String location,
                      @Param("summary") String summary,
                      @Param("reason") String reason,
                      @Param("engine") String engine,
                      @Param("model") String model,
                      @Param("promptVersion") String promptVersion,
                      @Param("latencyMs") Integer latencyMs,
                      @Param("tokens") Integer tokens,
                      @Param("attempts") int attempts,
                      @Param("at") LocalDateTime at);

    /** 拒绝：不改展示文本（它本来就不对外可见），只落结论、理由与审计元数据。 */
    int rejectReview(@Param("id") long id,
                     @Param("reason") String reason,
                     @Param("engine") String engine,
                     @Param("model") String model,
                     @Param("promptVersion") String promptVersion,
                     @Param("latencyMs") Integer latencyMs,
                     @Param("tokens") Integer tokens,
                     @Param("attempts") int attempts,
                     @Param("manualRequired") boolean manualRequired,
                     @Param("at") LocalDateTime at);

    /** AI 连续失败：保持 PENDING，标人工。绝不自动上架。 */
    int markReviewManual(@Param("id") long id,
                         @Param("reason") String reason,
                         @Param("engine") String engine,
                         @Param("model") String model,
                         @Param("promptVersion") String promptVersion,
                         @Param("latencyMs") Integer latencyMs,
                         @Param("tokens") Integer tokens,
                         @Param("attempts") int attempts);

    /** 兜底调度候选：待审 + 未转人工 + 已过冷却。 */
    List<Record> selectReviewCandidates(@Param("createdBefore") LocalDateTime createdBefore,
                                        @Param("maxAttempts") int maxAttempts,
                                        @Param("limit") int limit);

    /** 人工放行：只改审核列，不碰 title/description/location（提交时已是展示文本）。 */
    int markReviewApprovedManually(@Param("id") long id,
                                   @Param("reason") String reason,
                                   @Param("at") LocalDateTime at);

    /** 人工驳回：同上，只改审核列。 */
    int markReviewRejectedManually(@Param("id") long id,
                                   @Param("reason") String reason,
                                   @Param("at") LocalDateTime at);

    /** 人工审核队列：待审且已被标记为待人工（manual=1）。 */
    List<Record> selectManualReviewQueue(@Param("limit") int limit);

    /** 运维面板计数：{@code manualOnly=1} 时只数待人工的。 */
    long countReviewState(@Param("reviewState") String reviewState, @Param("manualOnly") int manualOnly);
}
