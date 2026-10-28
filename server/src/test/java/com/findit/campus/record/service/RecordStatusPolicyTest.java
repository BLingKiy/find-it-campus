package com.findit.campus.record.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.findit.campus.common.api.ErrorCode;
import com.findit.campus.common.error.BizException;
import com.findit.campus.record.domain.RecordStatus;

/**
 * 三态状态机（架构 §9.2，契约 §4.5）。
 *
 * <p>这张表同时驱动三处：详情/我的启事的 {@code allowedStatuses}、PATCH 的合法性、
 * 20002/20005 的错误体。规则一改，三处必须同时变；测试保证"改了有人看见"。
 *
 * <p>两个错误码的分工是本类的核心：
 * <ul>
 *   <li>状态图不允许（同值、终态再流转）→ 20002</li>
 *   <li>状态图允许但缺有效 MATCHED（PENDING→COMPLETED）→ 20005，且零写入</li>
 * </ul>
 */
@DisplayName("RecordStatusPolicy：三态流转与完成前置")
class RecordStatusPolicyTest {

    @Test
    @DisplayName("PENDING 可迁往 COMPLETED / CLOSED；CLAIMED 已从枚举删除")
    void pendingTransitions() {
        assertEquals(List.of(RecordStatus.COMPLETED, RecordStatus.CLOSED),
                RecordStatusPolicy.allowedNext(RecordStatus.PENDING));
        // 编译期即证明：枚举里没有 CLAIMED（旧值直接写出来是编译错误）
        assertEquals(3, RecordStatus.values().length);
    }

    @Test
    @DisplayName("没有有效 MATCHED 时 allowedStatuses 只剩 CLOSED（按钮置灰与 PATCH 前置同源）")
    void completedRequiresMatchedPair() {
        assertEquals(List.of(RecordStatus.CLOSED), RecordStatusPolicy.allowedNext(RecordStatus.PENDING, false));
        assertEquals(List.of(RecordStatus.COMPLETED, RecordStatus.CLOSED),
                RecordStatusPolicy.allowedNext(RecordStatus.PENDING, true));
    }

    @Test
    @DisplayName("COMPLETED / CLOSED 都是终态：allowedStatuses 为空数组，任何流转都被拒")
    void terminalStates() {
        assertTrue(RecordStatusPolicy.allowedNext(RecordStatus.COMPLETED).isEmpty());
        assertTrue(RecordStatusPolicy.allowedNext(RecordStatus.CLOSED).isEmpty());
        assertRejected(RecordStatus.COMPLETED, RecordStatus.CLOSED);
        assertRejected(RecordStatus.COMPLETED, RecordStatus.PENDING);
        assertRejected(RecordStatus.CLOSED, RecordStatus.PENDING);
        assertRejected(RecordStatus.CLOSED, RecordStatus.COMPLETED);
    }

    @Test
    @DisplayName("合法流转不抛异常")
    void legalTransitionsPass() {
        assertDoesNotThrow(() -> RecordStatusPolicy.requireTransition(RecordStatus.PENDING, RecordStatus.COMPLETED));
        assertDoesNotThrow(() -> RecordStatusPolicy.requireTransition(RecordStatus.PENDING, RecordStatus.CLOSED));
    }

    @Test
    @DisplayName("非法流转 → 20002，且错误体带 current / allowed")
    void illegalTransitionCarriesCurrentAndAllowed() {
        BizException ex = assertThrows(BizException.class,
                () -> RecordStatusPolicy.requireTransition(RecordStatus.CLOSED, RecordStatus.COMPLETED));
        assertEquals(ErrorCode.RECORD_ILLEGAL_STATUS_TRANSITION, ex.errorCode());
        Map<String, Object> data = ex.data();
        assertEquals("CLOSED", data.get("current"));
        // 终态的可选集合按契约给展示占位 ["(无)"]，空数组会让前端分不清"没有按钮"和"接口没给"
        assertEquals(List.of("(无)"), data.get("allowed"));
    }

    @Test
    @DisplayName("迁往当前状态本身也视为非法（重复点击不该静默成功）")
    void sameStatusIsRejected() {
        assertRejected(RecordStatus.PENDING, RecordStatus.PENDING);
        assertRejected(RecordStatus.COMPLETED, RecordStatus.COMPLETED);
    }

    @Test
    @DisplayName("没有 MATCHED pair 标记完成 → 20005，data 带 currentStatus/required/matchedPairCount")
    void completeWithoutMatchedPairIs20005() {
        BizException ex = assertThrows(BizException.class, () -> RecordStatusPolicy.requireCompletable(0));
        assertEquals(ErrorCode.PAIR_REQUIRED_FOR_COMPLETE, ex.errorCode());
        assertEquals(409, ex.errorCode().httpStatus().value());
        assertNotNull(ex.data());
        assertEquals("PENDING", ex.data().get("currentStatus"));
        assertEquals("MATCHED_PAIR", ex.data().get("required"));
        assertEquals(0, ex.data().get("matchedPairCount"));
    }

    @Test
    @DisplayName("有 MATCHED pair 时前置通过")
    void completeWithMatchedPairPasses() {
        assertDoesNotThrow(() -> RecordStatusPolicy.requireCompletable(1));
    }

    private static void assertRejected(RecordStatus current, RecordStatus target) {
        BizException ex = assertThrows(BizException.class, () -> RecordStatusPolicy.requireTransition(current, target));
        assertEquals(ErrorCode.RECORD_ILLEGAL_STATUS_TRANSITION, ex.errorCode());
        assertNotNull(ex.data(), "20002 必须带 current/allowed");
    }
}
