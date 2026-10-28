package com.findit.campus.pair.mapper;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 结构性测试：两条扫描 SQL 的候选口径（ZHYYKFX-88）。
 *
 * <p>为什么解析 XML 而不是只靠业务用例：这两条 SQL 的失败方式是<b>有人以后把冷却条件删了</b>，
 * 或者把降级重扫又写回"全量 + 状态过滤"。那种改动不会让任何单测变红 —— 它只会让 10 分钟一次的
 * 定时任务重新开始每轮空转、把冷却时钟不断往后推。把口径钉在 SQL 文本上，改错时构建直接红。
 *
 * <p>真正的执行计划与"是否命中索引"由 MySQL 证据负责（{@code docs/testing/v2/Z88-degraded-rescan.md}），
 * 这里只保证文本里写了索引能用的谓词（{@code pair_state} 等值 + {@code pair_scored_at} 范围）。
 */
@DisplayName("RecordMapper.xml：全量扫描的降级冷却 + 降级重扫只取 DEGRADED")
class PairScanTargetsSqlTest {

    @Test
    @DisplayName("全量扫描：DEGRADED 必须带 pair_scored_at < degradedBefore，PENDING/SCORING 仍用 staleBefore")
    void fullScanAppliesDegradedCooldown() throws Exception {
        String sql = statement("selectPairScanTargets").toLowerCase(Locale.ROOT);

        assertTrue(sql.contains("#{stalebefore}"), "PENDING/SCORING 仍按 stale 窗口判定");
        assertTrue(sql.contains("#{degradedbefore}"), "DEGRADED 必须带降级冷却下界");
        assertTrue(sql.matches("(?s).*pair_state = 'degraded'\\s+and \\(r\\.pair_scored_at is null"
                        + "\\s+or r\\.pair_scored_at &lt; #\\{degradedbefore\\}\\).*"),
                "降级分支必须把 pair_scored_at 与 degradedBefore 比较，否则每轮都重复取同一批");
        assertTrue(sql.contains("pair_state in ('pending', 'scoring')"), "未算/卡住的分支不能被删");
    }

    @Test
    @DisplayName("降级重扫：只取 DEGRADED、只带 degradedBefore、不混入 PENDING/SCORING")
    void degradedScanOnlySelectsDegraded() throws Exception {
        String sql = statement("selectDegradedScanTargets").toLowerCase(Locale.ROOT);

        assertTrue(sql.contains("pair_state = 'degraded'"), "降级重扫的等值谓词");
        assertFalse(sql.contains("'pending', 'scoring'"), "降级重扫不得把未算/卡住的记录再扫一遍");
        assertTrue(sql.contains("#{degradedbefore}"), "必须按降级冷却选材");
        assertTrue(sql.contains("#{completedcutoff}"), "过期 COMPLETED 不重算（与召回同口径）");
        assertTrue(sql.contains("limit #{limit}"), "必须有单轮上限");
        assertFalse(sql.contains("status = 'closed'") || sql.contains("pair_state = 'ready'"),
                "CLOSED/READY 不应成为降级重扫的目标");
    }

    private static String statement(String id) throws Exception {
        String xml = mapperXml();
        int start = xml.indexOf("<select id=\"" + id + "\"");
        assertTrue(start > 0, "找不到 " + id);
        int end = xml.indexOf("</select>", start);
        assertTrue(end > start, id + " 未闭合");
        return xml.substring(start, end);
    }

    private static String mapperXml() throws Exception {
        try (InputStream stream = PairScanTargetsSqlTest.class
                .getResourceAsStream("/mapper/record/RecordMapper.xml")) {
            assertNotNull(stream, "classpath 上找不到 RecordMapper.xml");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
