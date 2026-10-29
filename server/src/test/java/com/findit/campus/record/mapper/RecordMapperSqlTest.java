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

    @Test
    @DisplayName("发布闸门：公共列表 / 候选池 / 兜底扫描都通过 is_visible 过滤")
    void publicSurfacesRequireVisibility() throws Exception {
        assertTrue(normalized(fragment("listJoins")).contains("r.is_visible = 1"),
                "公共列表必须只出已审核记录");
        assertTrue(normalized(fragment("matchablePredicate")).contains("r.is_visible = 1"),
                "候选池与 /api/match 回表必须只出已审核记录");
        assertTrue(normalized(statement("selectPairScanTargets")).contains("r.is_visible = 1"),
                "未审核记录不得进入打分扫描");
    }

    private static String fragment(String id) throws Exception {
        String xml = mapperXml();
        int start = xml.indexOf("<sql id=\"" + id + "\"");
        assertTrue(start > 0, "找不到 SQL 片段 " + id);
        int end = xml.indexOf("</sql>", start);
        assertTrue(end > start, id + " 片段未闭合");
        return xml.substring(start, end);
    }

    @Test
    @DisplayName("审核写路径必须带 review_state='PENDING'，否则并发会覆盖已定稿结论")
    void reviewWritesAreConditional() throws Exception {
        for (String id : new String[]{"markReviewAttempt", "approveReview", "rejectReview",
                "markOwnerConfirmRequired", "confirmOwnerReview"}) {
            String sql = normalized(statement(id));
            assertTrue(sql.contains("review_state = 'pending'"),
                    id + " 缺少 review_state='PENDING' 条件");
        }
    }

    @Test
    @DisplayName("本人确认必须同时要求 review_manual_required=1：AI 正常时不能绕过审核")
    void ownerConfirmRequiresOwnerConfirmRequiredFlag() throws Exception {
        String sql = normalized(statement("confirmOwnerReview"));

        assertTrue(sql.contains("review_manual_required = 1"),
                "少了这个条件，任何 PENDING 记录都能被本人直接上架，闸门形同虚设");
        assertTrue(sql.contains("review_engine = 'rule_only'"),
                "本人确认的记录必须留可筛标记：它没有经过 AI 审核");
    }

    @Test
    @DisplayName("ops 人工改判写语句必须彻底不存在（只保留只读队列查询）")
    void noManualDecisionWritesRemain() throws Exception {
        String xml = normalized(mapperXml());

        assertFalse(xml.contains("markreviewapprovedmanually"), "人工放行写方法必须删除");
        assertFalse(xml.contains("markreviewrejectedmanually"), "人工驳回写方法必须删除");
        assertFalse(xml.contains("manual_approve"), "审计动作 MANUAL_APPROVE 不应再被写入");
        assertFalse(xml.contains("manual_reject"), "审计动作 MANUAL_REJECT 不应再被写入");
        assertTrue(xml.contains("selectownerconfirmqueue"), "只读排障队列应保留");
    }

    @Test
    @DisplayName("删除记录时 SQL 必须把 raw_* 置 NULL（人类 2026-10-29 拍板）")
    void deletePurgesRawColumns() throws Exception {
        String sql = normalized(statement("markDeleted"));

        assertTrue(sql.contains("raw_title = null"), "raw_title 必须清空");
        assertTrue(sql.contains("raw_description = null"), "raw_description 必须清空");
        assertTrue(sql.contains("raw_location = null"), "raw_location 必须清空");
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
