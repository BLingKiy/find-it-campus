package com.findit.campus.record.service;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.findit.campus.common.api.ErrorCode;
import com.findit.campus.common.error.BizException;
import com.findit.campus.record.domain.RecordStatus;

/**
 * 三态状态机：<b>唯一</b>的流转规则持有者（架构 §9.2，契约 §4.5）。
 *
 * <p>为什么单独成类而不是散在 service 的 if 里：{@code allowedStatuses}（详情/我的启事出参）、
 * PATCH 的合法性判定、20002 错误体里的 {@code allowed} 三处必须永远说同一句话。
 * 规则一旦有两份，改一处忘一处就是"详情页按钮点了才报 409"这种当场丢人的 bug。
 *
 * <p>状态机与「完成前置」<b>分成两个方法</b>，因为它们对应两个不同的错误码：
 * <ul>
 *   <li>{@link #requireTransition}：纯粹的状态图判定 —— 同值、终态再流转 → <b>20002</b></li>
 *   <li>{@link #requireCompletable}：{@code PENDING→COMPLETED} 的业务前置（有效 MATCHED pair）
 *       → <b>20005</b>，且调用方必须在任何 UPDATE 之前调用，保证零写入</li>
 * </ul>
 * 合成一个方法看似省事，实际会让「终态再点完成」和「没有配对就点完成」返回同一个码，
 * 前端就没法把「刷新页面」和「去找对方确认」两个动作区分开。
 *
 * <p>取值（A1 冻结，不得改）：{@code PENDING → COMPLETED / CLOSED}；{@code COMPLETED}/{@code CLOSED}
 * 都是终态，{@code CLAIMED} 已作废（收到即 10001，由 DTO 的 {@code @Pattern} 挡住）。
 */
public final class RecordStatusPolicy {

    private static final Map<RecordStatus, List<RecordStatus>> TRANSITIONS = new EnumMap<>(RecordStatus.class);

    static {
        TRANSITIONS.put(RecordStatus.PENDING, List.of(RecordStatus.COMPLETED, RecordStatus.CLOSED));
        TRANSITIONS.put(RecordStatus.COMPLETED, List.of());
        TRANSITIONS.put(RecordStatus.CLOSED, List.of());
    }

    private RecordStatusPolicy() {
    }

    /**
     * 当前状态可迁往的状态（契约 §4 的 {@code allowedStatuses}）。
     *
     * @return 有序列表；终态返回空列表（前端据此不渲染状态操作按钮）
     */
    public static List<RecordStatus> allowedNext(RecordStatus current) {
        return TRANSITIONS.getOrDefault(current, List.of());
    }

    /**
     * 「标记完成」是否可点：{@code PENDING} 且已有一对有效 MATCHED。
     *
     * <p>详情/我的启事的 {@code allowedStatuses} 用它过滤，与 PATCH 的前置检查共用同一个判断，
     * 避免"按钮可点但点了 409"这种自相矛盾的界面。
     */
    public static List<RecordStatus> allowedNext(RecordStatus current, boolean hasValidMatchedPair) {
        return allowedNext(current).stream()
                .filter(status -> status != RecordStatus.COMPLETED || hasValidMatchedPair)
                .toList();
    }

    /**
     * 判定一次流转是否合法（只看状态图），不合法抛 {@code 20002}。
     *
     * @param current 库中读到的当前状态
     * @param target  请求要迁往的状态
     * @throws BizException 20002（HTTP 409）
     */
    public static void requireTransition(RecordStatus current, RecordStatus target) {
        if (current == null) {
            throw illegal(null, "记录状态未知");
        }
        if (current == target) {
            throw illegal(current, "记录已处于状态 " + current);
        }
        if (!allowedNext(current).contains(target)) {
            throw illegal(current, "不允许从 " + current + " 流转到 " + target);
        }
    }

    /**
     * {@code PENDING→COMPLETED} 的业务前置：必须有一对有效 MATCHED pair。
     *
     * <p><b>调用位置在 UPDATE 之前</b>：不满足时抛 20005，事务里没有任何 DML，
     * {@code status}/{@code completed_at}/{@code updated_at} 三个值都不变（契约 §4.5 零写入口径）。
     *
     * @param matchedPairCount 有效 MATCHED pair 数（两端 confirmed_at 非空、两端未删且非 CLOSED）
     */
    public static void requireCompletable(int matchedPairCount) {
        if (matchedPairCount <= 0) {
            throw BizException.of(ErrorCode.PAIR_REQUIRED_FOR_COMPLETE,
                    ErrorCode.PAIR_REQUIRED_FOR_COMPLETE.message(),
                    completionErrorData(matchedPairCount));
        }
    }

    /** 契约 §4.5 的 20005 错误体；{@code matchedPairCount} 让前端能直接把按钮置灰。 */
    public static Map<String, Object> completionErrorData(int matchedPairCount) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("currentStatus", RecordStatus.PENDING.name());
        data.put("required", "MATCHED_PAIR");
        data.put("matchedPairCount", matchedPairCount);
        return data;
    }

    /** 契约 §6.1 的错误体：{@code allowed} 为空时用 {@code ["(无)"]}（契约给的展示占位）。 */
    public static Map<String, Object> transitionErrorData(RecordStatus current) {
        return transitionErrorData(current, true);
    }

    /** 带「完成前置」过滤的错误体：没有 MATCHED 时 {@code allowed} 里不出现 COMPLETED。 */
    public static Map<String, Object> transitionErrorData(RecordStatus current, boolean hasValidMatchedPair) {
        List<RecordStatus> allowed = current == null
                ? List.of()
                : allowedNext(current, hasValidMatchedPair);
        List<String> names = allowed.isEmpty()
                ? List.of("(无)")
                : allowed.stream().map(RecordStatus::name).toList();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("current", current == null ? null : current.name());
        data.put("allowed", names);
        return data;
    }

    private static BizException illegal(RecordStatus current, String detail) {
        return BizException.of(ErrorCode.RECORD_ILLEGAL_STATUS_TRANSITION, detail,
                transitionErrorData(current));
    }
}
