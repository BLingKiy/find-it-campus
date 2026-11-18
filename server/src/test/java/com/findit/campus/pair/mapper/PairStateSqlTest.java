package com.findit.campus.pair.mapper;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 结构性测试：DEGRADED 计数必须复用 MATCHABLE 谓词（P1-5）。
 *
 * <p>失败方式很隐蔽：对端 CLOSED/过期/删除后，只要计数 SQL 还数那条 LOCAL_KEYWORD pair，
 * 记录就会永远 DEGRADED，降级扫描每 60 分钟空转一次。业务单测用 mock 把计数当成既定事实，
 * 发现不了 SQL 少了过滤；所以这里直接断言 XML 文本。
 */
@DisplayName("PairScoreMapper.xml：降级计数只数仍可重算的 pair")
class PairStateSqlTest {

    @Test
    @DisplayName("批量降级计数：两端 MATCHABLE（未删/可见/非 CLOSED/保留期内）且按记录分组")
    void degradedCountAppliesMatchablePredicate() throws Exception {
        String sql = normalized(statement("countDegradedByRecordIds"));

        assertTrue(sql.contains("engine = 'local_keyword'"), "只数本地降级分");
        assertTrue(sql.contains("l.deleted_at is null"), "LOST 端已删不计数");
        assertTrue(sql.contains("f.deleted_at is null"), "FOUND 端已删不计数");
        assertTrue(sql.contains("l.is_visible = 1"), "LOST 端未过审不计数");
        assertTrue(sql.contains("f.is_visible = 1"), "FOUND 端未过审不计数");
        assertTrue(sql.contains("l.status <> 'closed'"), "LOST 端 CLOSED 不计数");
        assertTrue(sql.contains("f.status <> 'closed'"), "FOUND 端 CLOSED 不计数");
        assertTrue(sql.contains("#{cutoff}"), "COMPLETED 保留期下界必须由调用方传入");
        assertTrue(sql.contains("group by t.record_id"), "必须批量返回每条记录的计数");
    }

    private static String statement(String id) throws Exception {
        String xml = mapperXml();
        int start = xml.indexOf("<select id=\"" + id + "\"");
        assertTrue(start > 0, "找不到 " + id);
        int end = xml.indexOf("</select>", start);
        assertTrue(end > start, id + " 未闭合");
        return xml.substring(start, end);
    }

    private static String normalized(String sql) {
        return sql.replace("&gt;", ">").replace("&lt;", "<")
                .replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static String mapperXml() throws Exception {
        try (InputStream stream = PairStateSqlTest.class
                .getResourceAsStream("/mapper/pair/PairScoreMapper.xml")) {
            assertNotNull(stream, "classpath 上找不到 PairScoreMapper.xml");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
