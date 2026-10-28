package com.findit.campus.record.mapper;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 结构性测试：{@code RecordMapper.xml} 里两条与并发/召回口径相关的 WHERE（P2-1 / P2-4）。
 *
 * <p>为什么用"读 XML 断言"而不是只写业务单测：这两条约束的失效方式都是<b>SQL 条件被后来的人删掉</b>。
 * service 单测用 mock 把 {@code updateContent} 的返回值当成既定事实，永远不会发现 WHERE 少了
 * {@code status='PENDING'}；而扫描 SQL 少写保留期条件时，业务路径依然"能跑"，只是悄悄多花 LLM 额度。
 */
@DisplayName("RecordMapper.xml：条件更新与扫描保留期口径")
class RecordMapperSqlTest {

    @Test
    @DisplayName("P2-1 updateContent 的 WHERE 同时要求 deleted_at IS NULL 与 status='PENDING'")
    void updateContentRequiresPendingStatus() throws Exception {
        String sql = normalized(statement("updateContent"));

        assertTrue(sql.contains("where id = #{id}"), "更新必须按主键定位");
        assertTrue(sql.contains("deleted_at is null"), "逻辑删除的行不可改");
        assertTrue(sql.contains("status = 'pending'"),
                "缺少终态条件时，PUT 会覆盖并发 PATCH 已关闭/完成的记录（违反契约 §4.4）");
    }

    @Test
    @DisplayName("P2-4 扫描目标只取 PENDING 或保留期内 COMPLETED，不再用 status<>'CLOSED'")
    void scanTargetsApplyRetentionCutoff() throws Exception {
        String sql = normalized(statement("selectPairScanTargets"));

        assertTrue(sql.contains("r.status = 'pending'"), "PENDING 必须可扫");
        assertTrue(sql.contains("r.status = 'completed' and r.completed_at > #{completedcutoff}"),
                "COMPLETED 必须带保留期下界，过期记录不得再成为扫描目标");
        assertFalse(sql.contains("r.status <> 'closed'"),
                "旧条件会放行过期 COMPLETED：它不召回、详情 404，重算只是浪费 LLM 并产生死链通知");
    }

    private static String statement(String id) throws Exception {
        String xml = mapperXml();
        int start = xml.indexOf("<" + tagOf(xml, id) + " id=\"" + id + "\"");
        assertTrue(start > 0, "找不到语句 " + id);
        String closeTag = "</" + tagOf(xml, id) + ">";
        int end = xml.indexOf(closeTag, start);
        assertTrue(end > start, id + " 语句未闭合");
        return xml.substring(start, end);
    }

    private static String tagOf(String xml, String id) {
        if (xml.contains("<update id=\"" + id + "\"")) {
            return "update";
        }
        return "select";
    }

    private static String normalized(String sql) {
        return sql.replace("&gt;", ">").replace("&lt;", "<")
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
    }

    private static String mapperXml() throws Exception {
        try (InputStream stream = RecordMapperSqlTest.class
                .getResourceAsStream("/mapper/record/RecordMapper.xml")) {
            assertNotNull(stream, "classpath 上找不到 RecordMapper.xml");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
