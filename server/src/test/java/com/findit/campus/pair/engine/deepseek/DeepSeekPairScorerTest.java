package com.findit.campus.pair.engine.deepseek;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.findit.campus.pair.config.LlmProperties;
import com.findit.campus.pair.domain.PairSide;
import com.findit.campus.pair.engine.DegradedReason;
import com.findit.campus.pair.engine.LlmCircuitBreaker;
import com.findit.campus.pair.engine.PairScoreCommand;
import com.findit.campus.pair.engine.PairScoreResult;
import com.findit.campus.pair.engine.PairScoringMetrics;
import com.findit.campus.record.domain.RecordStatus;
import com.findit.campus.record.domain.RecordType;
import com.sun.net.httpserver.HttpServer;

/**
 * 降级触发条件（架构 §8.4）—— 用<b>本地假服务器</b>复现 401 / 超时 / 非 JSON / 分数越界。
 *
 * <p>为什么不用真实 DeepSeek：这四种情况在真实服务上无法按需复现（不能要求供应商返回 401），
 * 而验收要求"三种各复现一次"。用 {@link HttpServer} 在 127.0.0.1 上按模式应答，
 * 走的是<b>同一条</b> {@link DeepSeekPairScorer} 代码路径（同一个 HttpURLConnection、
 * 同一套状态码/解析/降级逻辑），因此证据有效。
 *
 * <p>本测试<b>不需要</b>真实密钥：所有用例的 key 都是显式的占位串（如 {@code dummy-for-test}），
 * 用来走到"有 key → 发请求"的分支；空 key 用例则验证"一次请求都不发"。
 */
@DisplayName("DeepSeekPairScorer：空 Key/401/超时/非 JSON/越界 → 全部降级且不抛异常")
class DeepSeekPairScorerTest {

    private HttpServer server;
    private final AtomicInteger requests = new AtomicInteger();
    private String portPrefix;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // 场景放在路径首段：{@code /{scenario}/chat/completions}，scorer 只负责拼 /chat/completions
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            String[] parts = exchange.getRequestURI().getPath().split("/");
            String scenario = parts.length > 1 && !parts[1].isBlank() ? parts[1] : "ok";
            try {
                switch (scenario) {
                    case "unauthorized" -> respond(exchange, 401, "{\"error\":\"unauthorized\"}");
                    case "nonjson" -> respond(exchange, 200, envelope("这不是 JSON"));
                    case "outofrange" -> respond(exchange, 200, envelope("{\"score\":1.5,\"reason\":\"越界\"}"));
                    case "slow" -> {
                        sleep(600);
                        respond(exchange, 200, envelope("{\"score\":0.5}"));
                    }
                    case "http500" -> respond(exchange, 500, "{\"error\":\"boom\"}");
                    default -> respond(exchange, 200,
                            "{\"choices\":[{\"message\":{\"content\":\"{\\\"score\\\":0.87,\\\"reason\\\":\\\"标题与描述高度重合\\\"}\"}}],"
                                    + "\"usage\":{\"prompt_tokens\":321,\"completion_tokens\":17}}");
                }
            } finally {
                exchange.close();
            }
        });
        server.start();
        portPrefix = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    @DisplayName("成功：engine=DEEPSEEK、分数在 [0,1]、latency>0、tokens 落库、reason 保留")
    void success() {
        PairScoreResult result = scorer("dummy-for-test", "ok").score(command());
        assertTrue(result.hasScore(), "成功路径必须有分数");
        assertEquals("DEEPSEEK", result.engine());
        assertNull(result.degradedReason());
        assertTrue(result.score().compareTo(java.math.BigDecimal.ZERO) >= 0
                && result.score().compareTo(java.math.BigDecimal.ONE) <= 0);
        assertTrue(result.latencyMs() != null && result.latencyMs() > 0, "latency 必须 > 0");
        assertEquals(321, result.promptTokens());
        assertEquals(17, result.completionTokens());
        assertEquals("标题与描述高度重合", result.reason());
    }

    @Test
    @DisplayName("空 Key：不发任何请求，直接 EMPTY_KEY 降级")
    void emptyKeyNoRequest() {
        PairScoreResult result = scorer("", "ok").score(command());
        assertEquals("LOCAL_KEYWORD", result.engine());
        assertEquals(DegradedReason.EMPTY_KEY.name(), result.degradedReason());
        assertNull(result.score(), "降级结果不带分数：由本地算法补");
        assertEquals(0, requests.get(), "空 Key 不得发起网络请求");
    }

    @Test
    @DisplayName("401：UNAUTHORIZED 降级，不抛异常")
    void unauthorized() {
        PairScoreResult result = scorer("dummy-for-test", "unauthorized").score(command());
        assertEquals(DegradedReason.UNAUTHORIZED.name(), result.degradedReason());
        assertNull(result.score());
    }

    @Test
    @DisplayName("超时：TIMEOUT 降级（read timeout 100ms < 服务端 600ms）")
    void timeout() {
        PairScoreResult result = scorer("dummy-for-test", "slow").score(command());
        assertEquals(DegradedReason.TIMEOUT.name(), result.degradedReason());
        assertNull(result.score());
    }

    @Test
    @DisplayName("非 JSON：NON_JSON 降级")
    void nonJson() {
        PairScoreResult result = scorer("dummy-for-test", "nonjson").score(command());
        assertEquals(DegradedReason.NON_JSON.name(), result.degradedReason());
    }

    @Test
    @DisplayName("分数越界：SCORE_OUT_OF_RANGE 降级")
    void scoreOutOfRange() {
        PairScoreResult result = scorer("dummy-for-test", "outofrange").score(command());
        assertEquals(DegradedReason.SCORE_OUT_OF_RANGE.name(), result.degradedReason());
    }

    @Test
    @DisplayName("5xx：HTTP_ERROR 降级")
    void httpError() {
        PairScoreResult result = scorer("dummy-for-test", "http500").score(command());
        assertEquals(DegradedReason.HTTP_ERROR.name(), result.degradedReason());
    }

    // ------------------------------------------------------------------ helpers

    private DeepSeekPairScorer scorer(String apiKey, String scenario) {
        LlmProperties properties = new LlmProperties();
        properties.setBaseUrl(portPrefix + "/" + scenario);
        properties.setModel("deepseek-chat");
        properties.setConnectTimeoutMs(500);
        properties.setReadTimeoutMs(100);
        properties.setTotalTimeoutMs(2_000);
        properties.validate();
        return new DeepSeekPairScorer(properties, new ObjectMapper(),
                new LlmCircuitBreaker(properties), new PairScoringMetrics(), apiKey);
    }

    private static PairScoreCommand command() {
        PairSide lost = new PairSide(1L, RecordType.LOST, RecordStatus.PENDING, null,
                "丢失校园卡", "在图书馆二楼丢的，卡套是蓝色的", "图书馆二楼", 3L, "证件",
                LocalDateTime.of(2026, 10, 20, 9, 0), 11L);
        PairSide found = new PairSide(2L, RecordType.FOUND, RecordStatus.PENDING, null,
                "捡到一张校园卡", "图书馆二楼自习室捡到，蓝色卡套", "图书馆二楼", 3L, "证件",
                LocalDateTime.of(2026, 10, 21, 10, 0), 12L);
        return new PairScoreCommand(lost, found, "pair-score-v1");
    }

    private static String envelope(String content) {
        return "{\"choices\":[{\"message\":{\"content\":" + quote(content) + "}}],"
                + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5}}";
    }

    private static String quote(String raw) {
        return "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
