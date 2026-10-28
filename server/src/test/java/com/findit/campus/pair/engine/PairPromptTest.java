package com.findit.campus.pair.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.findit.campus.pair.domain.PairSide;
import com.findit.campus.record.domain.RecordStatus;
import com.findit.campus.record.domain.RecordType;

/**
 * 打分 prompt 的双通道口径：只剥离身份类，地点/时间/特征必须保留。
 * v2.0 的 {@code maskLocation} 会把「第三教学楼 201」泛化掉，是本次改动要防的回归。
 */
@DisplayName("PairPrompt：比对通道只在 prompt 边界剥身份")
class PairPromptTest {

    @Test
    @DisplayName("prompt 不含手机号/带标签姓名，但保留精准地点与时间")
    void promptKeepsScenarioButNotIdentity() {
        PairSide side = new PairSide(1L, RecordType.LOST, RecordStatus.PENDING, null,
                "姓名：张三 丢了黑色钱包 13800000001",
                "在第三教学楼 201 丢的，电话 13800000001",
                "第三教学楼 201", 1L, "钱包", LocalDateTime.of(2026, 10, 21, 13, 30), 1L);
        PairScoreCommand command = new PairScoreCommand(side, side, PairPrompt.VERSION);

        String prompt = PairPrompt.userPrompt(command);

        assertFalse(prompt.contains("13800000001"), "手机号绝不能进 prompt");
        assertFalse(prompt.contains("张三"), "带标签姓名必须剥离");
        assertTrue(prompt.contains("第三教学楼 201"), "精准地点是匹配区分度，必须保留");
        assertTrue(prompt.contains("2026-10-21 13:30"), "时间必须保留");
        assertTrue(prompt.contains("黑色钱包"), "物品特征必须保留");
    }

    @Test
    @DisplayName("prompt 版本随语义变更升级为 pair-score-v2")
    void promptVersionBumped() {
        assertEquals("pair-score-v2", PairPrompt.VERSION);
    }
}
