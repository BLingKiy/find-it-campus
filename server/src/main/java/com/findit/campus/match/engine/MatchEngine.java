package com.findit.campus.match.engine;

import java.util.List;

/**
 * 匹配引擎的<b>唯一</b>业务抽象：给定查询，返回按分数降序的候选。
 *
 * <p>它挡住的变化是"换一种算法/换一种供养方"：本地关键词 vs 外部 embedding，
 * 调用方（{@code MatchService}）只认这个接口，切换靠 {@code findit.match.engine} 一个配置值完成。
 *
 * <p>约束（§6）：实现只能做纯文本打分/排序；不允许访问 {@code t_record} 之外的表，
 * 不允许知道 HTTP 概念，不允许把实体直接递回 web 层。
 *
 * <p>骨架阶段只有这个接口，没有实现 —— 实现属 3.2。
 */
public interface MatchEngine {

    /** 引擎标识，落 {@code t_match_result.impl}：LOCAL_KEYWORD / EXTERNAL_EMBEDDING。 */
    String impl();

    /**
     * 计算候选并按分数降序返回。
     *
     * @param candidates 候选池原文，由 {@link MatchCandidatePoolPort} 提供（上限由调用方控制）
     * @param query      查询参数（含 topN 与阈值）
     * @return 分数 {@code >= query.minScore()}、条数 {@code <= query.topN()} 的候选，降序
     * @throws MatchEngineException 引擎不可用（30002）或超时（30003）
     */
    List<MatchCandidate> match(List<MatchCandidateSource> candidates, MatchQuery query);
}
