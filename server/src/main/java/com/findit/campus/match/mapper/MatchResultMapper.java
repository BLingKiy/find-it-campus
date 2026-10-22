package com.findit.campus.match.mapper;

import org.apache.ibatis.annotations.Mapper;

import com.findit.campus.match.domain.MatchResult;

/**
 * {@code t_match_result} 的写出口（快照是只写的：没有接口读它，5.1 评估时查表）。
 *
 * <p>只有一个 insert 是刻意的：快照<b>不允许被修改</b>。改一条历史快照等于改写评估证据，
 * 那这张表存在的意义（5.1 的效果对比、6.2 的录屏证据）就没了。
 */
@Mapper
public interface MatchResultMapper {

    /** 写一行快照；自增主键回填到 {@link MatchResult#getId()}（即响应体的 {@code matchId}）。 */
    int insert(MatchResult result);
}
