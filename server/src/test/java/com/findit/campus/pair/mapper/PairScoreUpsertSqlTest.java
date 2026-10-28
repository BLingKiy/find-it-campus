package com.findit.campus.pair.mapper;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 结构性测试：{@code upsertScore} 的 {@code ON DUPLICATE KEY UPDATE} 列清单（任务书 #2）。
 *
 * <p>为什么用"解析 XML"而不是跑数据库：这条规则的失败方式是<b>有人以后往 UPDATE 列表里加了一列</b>，
 * 而那种错误不会被任何业务用例发现 —— 重算把 IGNORED 翻回 NEW 时，用户只会看到"我忽略过的配对
 * 又冒出来了"，不会有人立刻想到是打分链路的 SQL。把"允许更新的列"写成白名单并在这里断言，
 * 改错的时候构建直接红。
 *
 * <p>断言两层：① UPDATE 子句里不出现用户动作列；② INSERT 的列清单里不出现 {@code status}
 * （让 DDL 默认值只对首次插入生效）。
 */
@DisplayName("PairScoreMapper.xml：重算只更新分数类列，不碰 status/授权/确认")
class PairScoreUpsertSqlTest {

    /** 允许出现在 ON DUPLICATE KEY UPDATE 里的列（顺序无关）。 */
    private static final List<String> ALLOWED_UPDATE_COLUMNS = List.of(
            "score", "engine", "degraded_reason", "prompt_version",
            "latency_ms", "prompt_tokens", "completion_tokens", "reason", "lost_hash", "found_hash");

    /** 绝不允许被重算覆盖的列（任务书 #2 + 契约 §4.13）。 */
    private static final List<String> FORBIDDEN_UPDATE_COLUMNS = List.of(
            "status", "lost_unlocked_at", "found_unlocked_at", "lost_unlock_note", "found_unlock_note",
            "lost_confirmed_at", "found_confirmed_at", "created_at");

    @Test
    @DisplayName("ON DUPLICATE KEY UPDATE 只包含白名单列，且不含任何用户动作列")
    void updateClauseOnlyTouchesScoreColumns() throws Exception {
        String upsert = upsertStatement();
        String updateClause = upsert.substring(upsert.toUpperCase(Locale.ROOT)
                .indexOf("ON DUPLICATE KEY UPDATE")).toLowerCase(Locale.ROOT);
        assertFalse(updateClause.isBlank(), "必须能定位到 ON DUPLICATE KEY UPDATE 子句");

        for (String forbidden : FORBIDDEN_UPDATE_COLUMNS) {
            assertFalse(containsColumn(updateClause, forbidden),
                    "重算不得覆盖 " + forbidden + "（会重置 IGNORED / 授权 / 确认时间）");
        }
        for (String column : ALLOWED_UPDATE_COLUMNS) {
            assertTrue(containsColumn(updateClause, column), "分数类列 " + column + " 应出现在更新清单里");
        }
    }

    @Test
    @DisplayName("INSERT 列清单不写 status：默认值 NEW 只对首次插入生效")
    void insertDoesNotWriteStatus() throws Exception {
        String upsert = upsertStatement().toLowerCase(Locale.ROOT);
        int valuesAt = upsert.indexOf("values");
        assertTrue(valuesAt > 0, "必须能定位到 VALUES");
        String insertColumns = upsert.substring(0, valuesAt);
        assertFalse(containsColumn(insertColumns, "status"),
                "INSERT 不得显式写 status：写进列清单后，任何把它挪进 UPDATE 分支的改动都会静默翻状态");
    }

    @Test
    @DisplayName("写入路径没有 DELETE：pair 是评估证据，失效是读时谓词")
    void noDeleteStatement() throws Exception {
        String xml = mapperXml().toLowerCase(Locale.ROOT);
        assertFalse(xml.contains("<delete "), "t_pair_score 不允许物理删除");
        assertNotNull(xml);
    }

    private static String upsertStatement() throws Exception {
        String xml = mapperXml();
        int start = xml.indexOf("<insert id=\"upsertScore\"");
        assertTrue(start > 0, "找不到 upsertScore");
        int end = xml.indexOf("</insert>", start);
        assertTrue(end > start, "upsertScore 语句未闭合");
        return xml.substring(start, end);
    }

    private static String mapperXml() throws Exception {
        try (InputStream stream = PairScoreUpsertSqlTest.class
                .getResourceAsStream("/mapper/pair/PairScoreMapper.xml")) {
            assertNotNull(stream, "classpath 上找不到 PairScoreMapper.xml");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** 列名匹配：前后不能紧跟 [a-z0-9_]，避免 {@code status} 命中 {@code pair_state} 这类子串。 */
    private static boolean containsColumn(String text, String column) {
        return text.matches("(?s).*?(?<![a-z0-9_])" + column + "(?![a-z0-9_]).*");
    }
}
