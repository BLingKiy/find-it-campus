package com.findit.campus.record.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * 状态流转规则（架构基线 §7.4 流转表，1.1 未产出时取基线默认值）。
 *
 * <p>为什么给一张常量表写测试：{@code allowedStatuses}（详情出参）、PATCH 的合法性、20002 的
 * {@code allowed} 三处共用同一张表，规则一改，三处同时变。这条测试保证"规则变了有人看见"，
 * 而不是让某个页面悄悄多出一个按钮。
 */
@DisplayName("RecordStatusPolicy：状态流转表与 allowedStatuses")
class RecordStatusPolicyTest {

    @Test
    @DisplayName("PENDING 可迁往 CLAIMED / CLOSED")
    void pendingTransitions() {
        assertEquals(List.of(RecordStatus.CLAIMED, RecordStatus.CLOSED), RecordStatusPolicy.allowedNext(RecordStatus.PENDING));
    }

    @Test
    @DisplayName("CLAIMED 只能迁往 CLOSED（撤销认领回 PENDING 不允许，Q-03 推荐值）")
    void claimedTransitions() {
        assertEquals(List.of(RecordStatus.CLOSED), RecordStatusPolicy.allowedNext(RecordStatus.CLAIMED));
        assertRejected(RecordStatus.CLAIMED, RecordStatus.PENDING);
    }

    @Test
    @DisplayName("CLOSED 是终态：allowedStatuses 为空数组，任何流转都被拒")
    void closedIsTerminal() {
        assertTrue(RecordStatusPolicy.allowedNext(RecordStatus.CLOSED).isEmpty());
        assertRejected(RecordStatus.CLOSED, RecordStatus.PENDING);
        assertRejected(RecordStatus.CLOSED, RecordStatus.CLAIMED);
    }

    @Test
    @DisplayName("合法流转不抛异常")
    void legalTransitionsPass() {
        RecordStatusPolicy.requireTransition(RecordStatus.PENDING, RecordStatus.CLAIMED);
        RecordStatusPolicy.requireTransition(RecordStatus.PENDING, RecordStatus.CLOSED);
        RecordStatusPolicy.requireTransition(RecordStatus.CLAIMED, RecordStatus.CLOSED);
    }

    @Test
    @DisplayName("非法流转 → 20002，且错误体带 current / allowed（契约 §6.1）")
    void illegalTransitionCarriesCurrentAndAllowed() {
        BizException ex = assertThrows(BizException.class,
                () -> RecordStatusPolicy.requireTransition(RecordStatus.CLOSED, RecordStatus.CLAIMED));
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
    }

    private static void assertRejected(RecordStatus current, RecordStatus target) {
        BizException ex = assertThrows(BizException.class, () -> RecordStatusPolicy.requireTransition(current, target));
        assertEquals(ErrorCode.RECORD_ILLEGAL_STATUS_TRANSITION, ex.errorCode());
        assertFalse(ex.data() == null, "20002 必须带 current/allowed（契约 §6.1）");
    }
}
