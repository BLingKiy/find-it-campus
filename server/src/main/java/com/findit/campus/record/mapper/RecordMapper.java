package com.findit.campus.record.mapper;

import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.findit.campus.record.domain.Record;
import com.findit.campus.record.domain.RecordCandidate;
import com.findit.campus.record.domain.RecordStatus;
import com.findit.campus.record.domain.RecordSummary;
import com.findit.campus.record.domain.RecordType;

/**
 * {@code t_record} 的数据访问出口（原生 MyBatis + XML，ADR-004）。
 *
 * <p>两条贯穿所有查询的不变量，写在接口上是因为<b>忘带就是 bug</b>：
 * <ol>
 *   <li>读路径一律带 {@code deleted_at IS NULL}（ADR-006：逻辑删除对一切读接口不可见）</li>
 *   <li>写路径用"影响行数"表达命中与否：改到 0 行 ⇒ 记录不存在或已删 ⇒ 20001，
 *       比"先 SELECT 判存在再 UPDATE"少一次往返，也少一个 TOCTOU 窗口</li>
 * </ol>
 *
 * <p>所有 SQL 在 {@code resources/mapper/record/RecordMapper.xml}，这里不写注解 SQL ——
 * 分页、动态条件、{@code ORDER BY} 白名单分支放在 XML 里比拼字符串可读得多。
 */
@Mapper
public interface RecordMapper {

    /** 列表总数（与 {@link #selectSummaries} 用<b>同一个</b>条件对象，避免两处条件写歪）。 */
    long countByCondition(@Param("condition") RecordCondition condition);

    /** 当前页的卡片数据。{@code offset/limit} 由 service 从 page/pageSize 算出。 */
    List<RecordSummary> selectSummaries(@Param("condition") RecordCondition condition,
                                        @Param("offset") int offset,
                                        @Param("limit") int limit);

    /** 详情（含分类名）；已逻辑删除的返回 {@code null}。 */
    Record selectById(@Param("id") long id);

    /** 新增；自增主键回填到 {@link Record#getId()}。 */
    int insert(Record record);

    /** 改内容（不含 status）；返回影响行数，0 = 不存在/已删。 */
    int updateContent(Record record);

    /** 状态流转；返回影响行数，0 = 不存在/已删。 */
    int updateStatus(@Param("id") long id, @Param("status") RecordStatus status);

    /** 逻辑删除；返回影响行数，0 = 不存在/已删（契约 §6.2：重复删除明确报 20001）。 */
    int markDeleted(@Param("id") long id, @Param("deletedAt") LocalDateTime deletedAt);

    /**
     * 匹配候选池（只读）。口径与回表过滤保持一致：只取 {@code PENDING} 且未删除
     * （契约 §8.2：已领/已关闭对失主是噪声，不该被推荐出来）。
     *
     * @param type       目标类型；{@code null} = 不限类型
     * @param categoryId 分类过滤；{@code null} = 不限
     * @param limit      池上限（基线 500，防止匹配成本随全表线性增长）
     */
    List<RecordCandidate> selectCandidatePool(@Param("type") RecordType type,
                                              @Param("categoryId") Long categoryId,
                                              @Param("limit") int limit);

    /**
     * 按 id 批量取卡片（{@code /api/match} 回表补展示字段 + 过滤非法候选，ADR-008）。
     *
     * <p>这里再校验一次 {@code deleted_at IS NULL AND status='PENDING'} 不是冗余：
     * 候选池装载与结果回表之间可能已经过了几十毫秒，期间记录可能已被删除或认领。
     * 过滤放在 SQL 里，service 只按 id 对号入座。
     */
    List<RecordSummary> selectSummariesByIds(@Param("ids") List<Long> ids);
}
