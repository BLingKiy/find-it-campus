package com.findit.campus.match.engine;

/**
 * 候选池里的一条候选原文 —— 引擎能吃到的全部信息。
 *
 * <p>为什么引擎不自己去查 {@code t_record}：引擎一旦持有 Mapper/DAO，它就必须知道"未删除、状态可进候选池"
 * 这些业务不变量；这里由 service 层（3.1/3.2）负责过滤并通过 {@link MatchCandidatePoolPort} 喂给引擎，
 * 保证引擎是"纯文本 → 排名"的无状态计算，替换实现的成本最低。
 *
 * @param recordId    记录 id
 * @param title       标题，参与打分
 * @param description 详细描述，语义匹配的主文本输入
 * @param categoryId  记录所属分类（可空），供分类加权
 * @param location    地点文本，供地点就近加分
 */
public record MatchCandidateSource(
        Long recordId,
        String title,
        String description,
        Long categoryId,
        String location) {
}
