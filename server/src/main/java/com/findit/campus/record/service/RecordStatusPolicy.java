package com.findit.campus.record.service;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.findit.campus.common.api.ErrorCode;
import com.findit.campus.common.error.BizException;
import com.findit.campus.record.domain.RecordStatus;

/**
 * 状态机：<b>唯一</b>的流转规则持有者（架构基线 §7.4 流转表）。
 *
 * <p>为什么单独成类而不是散在 service 的 if 里：{@code allowedStatuses}（详情出参）、
 * PATCH 的合法性判定、20002 错误体里的 {@code allowed} 三处必须永远说同一句话。
 * 规则一旦有两份，改一处忘一处就是"详情页按钮点了才报 409"这种当场丢人的 bug。
 *
 * <p>取值说明（1.1 未产出，此处取基线默认值，绑定待拍板项）：
 * <ul>
 *   <li>{@code PENDING → CLAIMED / CLOSED}：正常认领或直接下架</li>
 *   <li>{@code CLAIMED → CLOSED}：撤销认领回到 PENDING <b>不允许</b>（Q-03 推荐值）</li>
 *   <li>{@code CLOSED}：终态（ADR：历史状态不允许被改写）</li>
 * </ul>
 * 三处规则都是常量级的，1.1 拍板后改这张表即可，不动接口与调用方。
 */
public final class RecordStatusPolicy {

    private static final Map<RecordStatus, List<RecordStatus>> TRANSITIONS = new EnumMap<>(RecordStatus.class);

    static {
        TRANSITIONS.put(RecordStatus.PENDING, List.of(RecordStatus.CLAIMED, RecordStatus.CLOSED));
        TRANSITIONS.put(RecordStatus.CLAIMED, List.of(RecordStatus.CLOSED));
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
     * 判定一次流转是否合法，不合法抛 {@code 20002}。
     *
     * <p>错误体按契约 §6.1 带 {@code current} 与 {@code allowed}：前端拿到后能直接刷新按钮，
     * 不需要"猜"当前变成了什么状态（状态可能已被别人改过）。
     *
     * @param current 库中读到的当前状态
     * @param target  请求要迁往的状态
     * @throws BizException 20002（HTTP 409）
     */
    public static void requireTransition(RecordStatus current, RecordStatus target) {
        if (current == target) {
            throw illegal(current, "记录已处于状态 " + current);
        }
        if (!allowedNext(current).contains(target)) {
            throw illegal(current, "不允许从 " + current + " 流转到 " + target);
        }
    }

    /** 契约 §6.1 的错误体：{@code allowed} 为空时用 {@code ["(无)"]}（契约给的展示占位）。 */
    public static Map<String, Object> transitionErrorData(RecordStatus current) {
        List<String> allowed = allowedNext(current).isEmpty()
                ? List.of("(无)")
                : allowedNext(current).stream().map(RecordStatus::name).toList();
        return Map.of("current", current.name(), "allowed", allowed);
    }

    private static BizException illegal(RecordStatus current, String detail) {
        return BizException.of(ErrorCode.RECORD_ILLEGAL_STATUS_TRANSITION, detail,
                new LinkedHashMap<>(transitionErrorData(current)));
    }
}
