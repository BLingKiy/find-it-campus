/**
 * 失物/招领记录（记录上下文）。<b>{@code t_record} 的唯一写方</b>。
 *
 * <p>一张表承载两类启事（ADR-001），用 {@code type} 区分；删除走 {@code deleted_at} 逻辑删除。
 * match 上下文只读它（经 {@code MatchCandidatePoolPort}），不直接写。
 */
package com.findit.campus.record;
