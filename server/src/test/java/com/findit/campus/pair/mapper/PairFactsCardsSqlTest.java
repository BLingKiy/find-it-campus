package com.findit.campus.pair.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 结构性测试：卡片批量查询的四条硬约束，用解析 XML 的方式钉住。
 *
 * <p>为什么不在单测里跑库：这些约束的失败方式是"某人以后改了一句 SQL"——
 * 去掉 {@code CLOSED} 过滤会让已关闭记录的卡片复活，去掉 {@code p.score >= threshold}
 * 会让低分卡片混进列表，{@code SELECT other.contact} 会把号码送进 Java 对象。
 * 这三种改动都不会让任何业务用例变红，只有静态断言能在构建期拦住。
 *
 * <p>真实数据的行为验证见 {@code docs/testing/v2/E1b-record-pairs.md}（连 MySQL 的请求/响应）。
 */
@DisplayName("PairFactsMapper.xml：卡片查询只取有效高分 pair，且不 SELECT 号码")
class PairFactsCardsSqlTest {

    @Test
    @DisplayName("低分不进卡片，失效（删除 / CLOSED / COMPLETED 到期）一律剔除")
    void cardsQueryKeepsOnlyUsablePairsAboveThreshold() throws Exception {
        String sql = cardsSelect();

        assertTrue(sql.contains("p.score &gt;= #{threshold}"), "卡片下界必须由参数传入（不硬编码 0.75）");
        assertTrue(sql.contains("l.deleted_at IS NULL"), "本人记录已删除不得出卡");
        assertTrue(sql.contains("f.deleted_at IS NULL"), "对端记录已删除不得出卡");
        assertTrue(sql.contains("l.status &lt;&gt; 'CLOSED'"), "本人记录 CLOSED 不得出卡");
        assertTrue(sql.contains("f.status &lt;&gt; 'CLOSED'"), "对端记录 CLOSED 不得出卡");
        assertTrue(sql.contains("l.completed_at &gt; #{cutoff}"), "本人 COMPLETED 到期不得出卡");
        assertTrue(sql.contains("f.completed_at &gt; #{cutoff}"), "对端 COMPLETED 到期不得出卡");
        assertTrue(sql.contains("l.is_visible = 1"), "本人记录未过审不得出卡");
        assertTrue(sql.contains("f.is_visible = 1"), "对端记录未过审不得出卡");
    }

    @Test
    @DisplayName("发布闸门：每一条事实查询都带 is_visible（新增查询漏写会在构建期红）")
    void everyFactsSelectRequiresVisibility() throws Exception {
        String xml = mapperXml();
        int cursor = 0;
        int checked = 0;
        while ((cursor = xml.indexOf("<select id=\"", cursor)) > 0) {
            int end = xml.indexOf("</select>", cursor);
            assertTrue(end > cursor, "select 未闭合");
            String statement = xml.substring(cursor, end);
            assertTrue(statement.contains("is_visible = 1"),
                    "事实查询缺少 is_visible："
                            + statement.substring(0, Math.min(80, statement.length())));
            checked++;
            cursor = end;
        }
        assertEquals(5, checked, "PairFactsMapper 的 5 条事实查询都应被检查");
    }

    @Test
    @DisplayName("排序由服务端定死：score 降序、同分 pair_id 升序")
    void orderingIsServerSide() throws Exception {
        assertTrue(cardsSelect().contains("ORDER BY t.record_id ASC, p.score DESC, p.id ASC"),
                "顺序是接口语义；改排序等于改契约，必须显式改这一行");
    }

    @Test
    @DisplayName("号码只以布尔出现：没有 contact 列，也没有 contact 别名")
    void contactIsNeverSelected() throws Exception {
        String sql = cardsSelect();

        assertTrue(sql.contains("(other.contact IS NOT NULL AND other.contact &lt;&gt; '')"),
                "只允许这一个『对方有没有填号』的布尔表达式");
        assertEquals(2, occurrences(sql, "other.contact"),
                "other.contact 只应出现在这个布尔表达式里（两次：IS NOT NULL / <> ''）");
        assertFalse(sql.matches("(?s).*\\bcontact\\b\\s+AS\\b.*"),
                "不得把 contact 起别名带出查询");
        assertFalse(sql.contains(" AS contact"), "不得把 contact 起别名带出查询");
    }

    @Test
    @DisplayName("整份 Mapper 没有 ${} 拼接（注入面的静态防线）")
    void noStringInterpolation() throws Exception {
        assertFalse(mapperXml().contains("${"), "ORDER BY / IN 列表都只能走 #{} 或 Java 枚举白名单");
    }

    private static String cardsSelect() throws Exception {
        String xml = mapperXml();
        int start = xml.indexOf("<select id=\"selectCardsByRecordIds\"");
        assertTrue(start > 0, "找不到 selectCardsByRecordIds");
        int end = xml.indexOf("</select>", start);
        assertTrue(end > start, "selectCardsByRecordIds 未闭合");
        return xml.substring(start, end);
    }

    private static String mapperXml() throws Exception {
        try (InputStream stream = PairFactsCardsSqlTest.class
                .getResourceAsStream("/mapper/pair/PairFactsMapper.xml")) {
            assertNotNull(stream, "classpath 上找不到 PairFactsMapper.xml");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        int from = 0;
        while ((from = text.indexOf(needle, from)) >= 0) {
            count++;
            from += needle.length();
        }
        return count;
    }
}
