package com.findit.campus.pair.engine.deepseek;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.findit.campus.pair.config.LlmProperties;
import com.findit.campus.pair.engine.DegradedReason;
import com.findit.campus.pair.engine.LlmCircuitBreaker;
import com.findit.campus.pair.engine.PairPrompt;
import com.findit.campus.pair.engine.PairScoreCommand;
import com.findit.campus.pair.engine.PairScoreResult;
import com.findit.campus.pair.engine.PairScorer;
import com.findit.campus.pair.engine.PairScoringMetrics;

/**
 * DeepSeek（OpenAI 兼容 Chat Completions）打分器，架构 §8.1–§8.4 的实现。
 *
 * <p><b>它只做一件事：发一次请求、把结果翻译成 {@link PairScoreResult}</b>。
 * 重试 0 次（超时/401 都是降级触发器，重试会重复计费）；补本地分不在它的职责里。
 *
 * <p><b>密钥</b>：只从 {@code @Value("${DEEPSEEK_API_KEY:}")} 读（等价于环境变量）。
 * 这个值不进日志、不进异常消息、不进响应体；{@link #describeHttpFailure} 只打印状态码与
 * 脱敏后的错误码。连 {@code Authorization} 头都不构造进日志字符串。
 *
 * <p><b>超时</b>：用 {@link HttpURLConnection} 而不是 {@code java.net.http.HttpClient}，
 * 因为只有前者能<b>分别</b>设置 connect 与 read 超时（{@code HttpClient} 只有 connect + 整体
 * request timeout）。额外用一个 {@code totalTimeoutMs} 的事后判定兜住"多段慢速响应"：
 * 总耗时超过它，即使拿到了 200 也按 TIMEOUT 丢弃。
 *
 * <p><b>为什么不记录 prompt/response</b>：即便 prompt 已打码，日志留存原文等于自建语料库；
 * 排障需要的是"哪一对、什么原因、多久、多少 token"，这些都够定位。
 */
@Component
public class DeepSeekPairScorer implements PairScorer {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekPairScorer.class);

    private static final DateTimeFormatter LOG_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int REASON_MAX_LENGTH = 200;

    private final LlmProperties properties;
    private final ObjectMapper objectMapper;
    private final LlmCircuitBreaker circuitBreaker;
    private final PairScoringMetrics metrics;
    private final String apiKey;

    public DeepSeekPairScorer(LlmProperties properties,
                              ObjectMapper objectMapper,
                              LlmCircuitBreaker circuitBreaker,
                              PairScoringMetrics metrics,
                              @Value("${DEEPSEEK_API_KEY:}") String apiKey) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.circuitBreaker = circuitBreaker;
        this.metrics = metrics;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
    }

    @Override
    public PairScoreResult score(PairScoreCommand command) {
        // 空 Key 是"没配"，不是"配了不通"：不打网络、不计入熔断（否则一启动就把熔断打开）
        if (apiKey.isEmpty()) {
            metrics.degraded(DegradedReason.EMPTY_KEY);
            return PairScoreResult.degraded(DegradedReason.EMPTY_KEY, 0);
        }
        if (!circuitBreaker.allowRequest()) {
            metrics.degraded(DegradedReason.CIRCUIT_OPEN);
            return PairScoreResult.degraded(DegradedReason.CIRCUIT_OPEN, 0);
        }

        long startedAt = System.nanoTime();
        metrics.llmCallStarted();
        HttpURLConnection connection = null;
        try {
            connection = open(command);
            int status = connection.getResponseCode();
            if (status == 401 || status == 403) {
                return fail(command, DegradedReason.UNAUTHORIZED, startedAt, status);
            }
            if (status < 200 || status >= 300) {
                return fail(command, DegradedReason.HTTP_ERROR, startedAt, status);
            }
            String body = readBody(connection.getInputStream());
            long elapsedMs = elapsedMs(startedAt);
            if (elapsedMs > properties.getTotalTimeoutMs()) {
                // 事后判定：多段慢速响应可能绕开单次 read 超时，拿到结果也按超时处理
                metrics.degraded(DegradedReason.TIMEOUT);
                log.warn("pair llm degraded engine={} reason=TIMEOUT latencyMs={} totalTimeoutMs={}",
                        command.lostId() + "-" + command.foundId(), elapsedMs, properties.getTotalTimeoutMs());
                return PairScoreResult.degraded(DegradedReason.TIMEOUT, (int) elapsedMs);
            }
            PairScoreResult result = parse(command, body, (int) elapsedMs);
            if (result.hasScore()) {
                circuitBreaker.recordSuccess();
                metrics.llmSucceeded();
                log.info("pair llm ok lostId={} foundId={} engine={} score={} latencyMs={} promptTokens={} completionTokens={} model={}",
                        command.lostId(), command.foundId(), result.engine(), result.score(),
                        result.latencyMs(), result.promptTokens(), result.completionTokens(),
                        properties.getModel());
            } else {
                circuitBreaker.recordFailure();
                metrics.degraded(DegradedReason.valueOf(result.degradedReason()));
                log.warn("pair llm degraded lostId={} foundId={} reason={} latencyMs={}",
                        command.lostId(), command.foundId(), result.degradedReason(), result.latencyMs());
            }
            return result;
        } catch (SocketTimeoutException e) {
            return fail(command, DegradedReason.TIMEOUT, startedAt, -1);
        } catch (IOException e) {
            // 连接被拒/DNS 失败/响应流中断：都归到 HTTP_ERROR（不是超时，也不是非 JSON）
            return fail(command, DegradedReason.HTTP_ERROR, startedAt, -1);
        } catch (RuntimeException e) {
            return fail(command, DegradedReason.HTTP_ERROR, startedAt, -1);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private HttpURLConnection open(PairScoreCommand command) throws IOException {
        URL url = new URL(stripTrailingSlash(properties.getBaseUrl()) + "/chat/completions");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(properties.getConnectTimeoutMs());
        connection.setReadTimeout(properties.getReadTimeoutMs());
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Accept", "application/json");
        // 唯一出现密钥的地方：请求头。不要把它复制到任何字符串/日志/MDC
        connection.setRequestProperty("Authorization", "Bearer " + apiKey);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", properties.getModel());
        payload.put("messages", PairPrompt.messages(command));
        payload.put("temperature", properties.getTemperature());
        payload.put("max_tokens", properties.getMaxTokens());
        payload.put("stream", false);
        payload.put("response_format", Map.of("type", "json_object"));
        byte[] body = objectMapper.writeValueAsBytes(payload);
        try (OutputStream out = connection.getOutputStream()) {
            out.write(body);
        }
        return connection;
    }

    /**
     * 解析契约 §8.3：{@code choices[0].message.content} 是 JSON 对象，{@code score} 是 [0,1] 数字。
     *
     * <p>模型偶尔会套一层 ```json 代码块（即便要求了 json_object），这里剥掉再解析 ——
     * 这不是"宽容"，而是把"可预期的格式抖动"与"真的坏了"分开：抖动按成功处理，
     * 真的坏了才是 NON_JSON 降级。
     */
    private PairScoreResult parse(PairScoreCommand command, String body, int latencyMs) {
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (IOException e) {
            return PairScoreResult.degraded(DegradedReason.NON_JSON, latencyMs);
        }
        JsonNode content = root.path("choices").path(0).path("message").path("content");
        if (!content.isTextual() || content.asText().isBlank()) {
            return PairScoreResult.degraded(DegradedReason.NON_JSON, latencyMs);
        }
        String json = stripCodeFence(content.asText().trim());
        JsonNode scored;
        try {
            scored = objectMapper.readTree(json);
        } catch (IOException e) {
            return PairScoreResult.degraded(DegradedReason.NON_JSON, latencyMs);
        }
        JsonNode scoreNode = scored.get("score");
        if (scoreNode == null || !scoreNode.isNumber()) {
            return PairScoreResult.degraded(DegradedReason.SCORE_OUT_OF_RANGE, latencyMs);
        }
        BigDecimal score = scoreNode.decimalValue();
        if (score.compareTo(BigDecimal.ZERO) < 0 || score.compareTo(BigDecimal.ONE) > 0) {
            return PairScoreResult.degraded(DegradedReason.SCORE_OUT_OF_RANGE, latencyMs);
        }
        Integer promptTokens = intOrNull(root.path("usage").path("prompt_tokens"));
        Integer completionTokens = intOrNull(root.path("usage").path("completion_tokens"));
        if (promptTokens == null || completionTokens == null) {
            // 成功但 usage 缺失：证据不完整，写 NULL 不估算（契约 §8.3 第 4 条）
            log.warn("pair llm usage 缺失 lostId={} foundId={}（tokens 写 NULL，不估算）",
                    command.lostId(), command.foundId());
        }
        String reason = scored.path("reason").isTextual() ? scored.path("reason").asText() : "";
        if (reason.length() > REASON_MAX_LENGTH) {
            reason = reason.substring(0, REASON_MAX_LENGTH);
        }
        return PairScoreResult.llm(score, latencyMs, promptTokens, completionTokens, reason);
    }

    private PairScoreResult fail(PairScoreCommand command, DegradedReason reason, long startedAt, int status) {
        int latencyMs = (int) elapsedMs(startedAt);
        circuitBreaker.recordFailure();
        metrics.degraded(reason);
        log.warn("pair llm degraded lostId={} foundId={} reason={} latencyMs={} httpStatus={} at={}",
                command.lostId(), command.foundId(), reason, latencyMs, status,
                LocalDateTime.now().format(LOG_TIME));
        return PairScoreResult.degraded(reason, latencyMs);
    }

    private static long elapsedMs(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }

    private static String readBody(InputStream stream) throws IOException {
        try (InputStream in = stream) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Integer intOrNull(JsonNode node) {
        return node != null && node.isNumber() ? node.asInt() : null;
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    static String stripCodeFence(String text) {
        if (!text.startsWith("```")) {
            return text;
        }
        int firstNewline = text.indexOf('\n');
        if (firstNewline < 0) {
            return text;
        }
        String withoutHeader = text.substring(firstNewline + 1);
        int closing = withoutHeader.lastIndexOf("```");
        return closing < 0 ? withoutHeader : withoutHeader.substring(0, closing).trim();
    }

    /** 供单测构造最小请求体，避免把 prompt 原文写进测试的期望字符串里。 */
    static List<Map<String, String>> messagesOf(PairScoreCommand command) {
        return PairPrompt.messages(command);
    }
}
