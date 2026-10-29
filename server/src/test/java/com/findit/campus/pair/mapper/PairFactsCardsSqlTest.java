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

        assertTrue(sql.contains("<include refid=\"score_at_or_above\"/>"),
                "卡片下界必须走共享片段（不硬编码 0.75，也不允许两处阈值分叉）");
        String fragment = scoreFragment();
        assertTrue(fragment.contains("p.engine = 'LOCAL_KEYWORD' AND p.score &gt;= #{localThreshold}"),
                "本地引擎用 localThreshold");
        assertTrue(fragment.contains("p.engine &lt;&gt; 'LOCAL_KEYWORD' AND p.score &gt;= #{threshold}"),
                "其余引擎用默认 threshold");
        assertTrue(sql.contains("l.deleted_at IS NULL"), "本人记录已删除不得出卡");
        assertTrue(sql.contains("f.deleted_at IS NULL"), "对端记录已删除不得出卡");
        assertTrue(sql.contains("l.status &lt;&gt; 'CLOSED'"), "本人记录 CLOSED 不得出卡");
        assertTrue(sql.contains("f.status &lt;&gt; 'CLOSED'"), "对端记录 CLOSED 不得出卡");
        assertTrue(sql.contains("l.completed_at &gt; #{cutoff}"), "本人 COMPLETED 到期不得出卡");
        assertTrue(sql.contains("f.completed_at &gt; #{cutoff}"), "对端 COMPLETED 到期不得出卡");
    }

    @Test
    @DisplayName("低分计数与卡片用的是同一个阈值片段：不会出现『卡片出了、计数还算低分』")
    void lowConfidenceUsesSameFragment() throws Exception {
        String xml = mapperXml();
        int lowStart = xml.indexOf("<select id=\"countLowConfidence\"");
        int lowEnd = xml.indexOf("</select>", lowStart);
        String low = xml.substring(lowStart, lowEnd);
        assertTrue(low.contains("NOT <include refid=\"score_at_or_above\"/>"),
                "低分计数必须是同一片段的补集");
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

    private static String scoreFragment() throws Exception {
        String xml = mapperXml();
        int start = xml.indexOf("<sql id=\"score_at_or_above\">");
        assertTrue(start > 0, "找不到 score_at_or_above 片段");
        int end = xml.indexOf("</sql>", start);
        assertTrue(end > start, "score_at_or_above 未闭合");
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
