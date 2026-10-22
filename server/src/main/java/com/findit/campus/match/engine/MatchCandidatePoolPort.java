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
     * <p><b>类型口径（3.1 澄清，以契约为准）</b>：{@code targetType} 就是"本次要找的记录类型"，
     * 因此候选池取的是<b>同类型</b>记录 —— 契约 §8.1 写死"失主想找招领帖 ⇒ {@code type=FOUND}"，
     * 且 §8.2 的示例里 {@code inputType=FOUND} 与 {@code items[].type=FOUND} 同值；
     * {@code db/seed.sql} 的样例 S1（输入 text 描述丢钱包、type=FOUND）期望 top1 是 13 号 FOUND 记录，
     * 若按"取相反类型"实现，返回的会是 1~10 号 LOST 记录，与契约和样例集同时冲突。
     * （{@code RecordType#opposite()} 是骨架期的另一种口径残留，不在这里使用。）
     *
     * @param targetType 要找的记录类型；{@code null} = 不限类型（此时池子里两类都有）
     * @param categoryId 分类过滤/加权参数，可空表示不限
     * @param limit      候选池上限（基线 500，防止匹配成本随全表线性增长）
     * @return 未删除、且状态允许进入候选池（{@code PENDING}）的记录原文
     */
    List<MatchCandidateSource> load(RecordType targetType, Long categoryId, int limit);
}
