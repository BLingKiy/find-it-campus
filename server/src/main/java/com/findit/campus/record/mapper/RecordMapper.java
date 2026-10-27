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

    /** 新增；自增主键回填到 {@link Record#getId()}。status 固定 PENDING，completed_at 不写（默认 NULL）。 */
    int insert(Record record);

    /** 改内容（不含 status）；返回影响行数，0 = 不存在/已删。 */
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
}
