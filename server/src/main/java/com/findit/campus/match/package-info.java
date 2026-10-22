/**
 * 语义匹配（匹配上下文）。<b>{@code t_match_result} 的唯一写方</b>。
 *
 * <p>职责边界（ADR-008）：{@code MatchService} 编排"校验 → 取候选池 → 调引擎 → 回表补展示字段 → 写快照"，
 * 引擎只输出 {@code recordId + score + reason}。
 */
package com.findit.campus.match;
