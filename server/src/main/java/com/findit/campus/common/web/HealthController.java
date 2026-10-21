package com.findit.campus.common.web;

import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.findit.campus.common.api.ApiResponse;
import com.findit.campus.common.api.ErrorCode;
import com.findit.campus.common.config.MatchEngineConfig;

/**
 * 骨架级健康检查 {@code GET /api/health}（契约 {@code docs/api-contract.md} §2）。
 *
 * <p>为什么它不是"永远返回 200 的假探针"：这个接口的用途是判断"前端能不能连、库通不通"，
 * 一个恒真的 UP 在演示排障时毫无价值。因此这里真连一次数据库 —— 拿不到连接就按契约返回
 * HTTP 503 + code 10005 + {@code db:"DOWN"}，<b>明确失败，不许挂住</b>。
 * 注意这里选的是 503 而不是 10005 默认映射的 500：两份基线文档口径不一致，详见方法内注释，待人类拍板改哪一处。
 *
 * <p>不靠 hang 的保证来自数据源侧超时：
 * {@code spring.datasource.hikari.connection-timeout=3s} 与 JDBC URL 上的 {@code connectTimeout/socketTimeout}，
 * 探针本身另加 {@code isValid(1s)}。
 */
@RestController
@RequestMapping("/api")
public class HealthController {

    private static final Logger log = LoggerFactory.getLogger(HealthController.class);

    private final DataSource dataSource;
    private final MatchEngineConfig matchEngineConfig;
    private final String version;

    public HealthController(DataSource dataSource,
                            MatchEngineConfig matchEngineConfig,
                            @Value("${findit.version:0.0.1-SNAPSHOT}") String version) {
        this.dataSource = dataSource;
        this.matchEngineConfig = matchEngineConfig;
        this.version = version;
    }

    @GetMapping("/health")
    public ResponseEntity<ApiResponse<Map<String, Object>>> health() {
        String db = probeDatabase();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", "UP".equals(db) ? "UP" : "DOWN");
        data.put("db", db);
        data.put("version", version);
        data.put("engine", matchEngineConfig.engine());

        if ("DOWN".equals(db)) {
            log.warn("健康检查失败：数据库不可用");
            // ⚠ 基线与契约在这里不一致，按契约执行，已报人类确认（见本文件类注释）：
            // docs/api-contract.md §2 要求 DB 不可用时 HTTP 503 + code 10005；
            // docs/architecture-baseline.md §6.2 表里 10005 默认映射 500。
            // 取 503：body 的 code 仍是 10005，只是 HTTP 层多选了一个语义更准的状态码。
            return new ResponseEntity<>(ApiResponse.fail(ErrorCode.INTERNAL_ERROR, data), new HttpHeaders(),
                    HttpStatusCode.valueOf(HttpStatus.SERVICE_UNAVAILABLE.value()));
        }
        return ResponseEntity.ok(ApiResponse.success(data));
    }

    private String probeDatabase() {
        try (Connection connection = dataSource.getConnection()) {
            return connection.isValid(1) ? "UP" : "DOWN";
        } catch (Exception e) {
            log.warn("数据库连通性探测失败: {}", e.getMessage());
            return "DOWN";
        }
    }
}
