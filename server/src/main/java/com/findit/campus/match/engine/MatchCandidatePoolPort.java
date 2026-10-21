package com.findit.campus.match.engine;

import com.findit.campus.record.domain.RecordType;

import java.util.List;

/**
 * 候选池出口：引擎取候选文本的通道（唯一的"读 t_record"入口，由 record 侧实现）。
 *
 * <p>为什么要有它：引擎若自己依赖 Mapper，就会出现"算法组件反向压住业务数据访问层"的耦合，
 * 用一个端口接口把方向掰正 —— 由 service 层组合"谁给数据、谁打分"，引擎只依赖这个接口。
 *
 * <p>骨架阶段只有这个接口，实现属 3.2（届时实现落在 {@code match} 包内并调用 {@code record.mapper}，
 * 或直接由 {@code record.service} 实现该接口 —— 二选一由 3.2 定，接口不变）。
 */
public interface MatchCandidatePoolPort {

    /**
     * 取候选池原文。
     *
     * @param targetType 要找的类型：找 LOST 时从 FOUND 里取候选（跨类型匹配），反之亦然
     * @param categoryId 分类过滤/加权参数，可空表示不限
     * @param limit      候选池上限（基线 500，防止匹配成本随全表线性增长）
     * @return 未删除、且状态允许进入候选池的记录原文
     */
    List<MatchCandidateSource> load(RecordType targetType, Long categoryId, int limit);
}
