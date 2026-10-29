package com.findit.campus.review.postprocess;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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
import com.findit.campus.review.config.ReviewProperties;

/**
 * DeepSeek（OpenAI 兼容 Chat Completions）的发布后处理客户端。
 *
 * <p>与 {@code DeepSeekPairScorer} 共享 {@link LlmProperties}（base-url/model）与同一个
 * {@code DEEPSEEK_API_KEY}，但<b>不复用它的重试/熔断语义</b>：审核失败不能降级成本地算法后
 * 仍然上架 —— 那等于绕开闸门。这里只发一次，失败由 {@code ReviewGateService} 计数并最终转人工。
 *
 * <p>密钥只出现在 {@code Authorization} 头；日志只打 recordId、状态、耗时、token、模型名。
 * 不记录 prompt/响应原文：即便输入已剥离身份，日志留存整段文本仍会变成第二份语料库。
 */
@Component
public class DeepSeekPostprocessClient implements PostprocessClient {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekPostprocessClient.class);

    static final String ENGINE = "DEEPSEEK";
    private static final int REASON_MAX_LENGTH = 200;

    private final LlmProperties llmProperties;
    private final ReviewProperties reviewProperties;
    private final ObjectMapper objectMapper;
    private final String apiKey;

    public DeepSeekPostprocessClient(LlmProperties llmProperties,
                                     ReviewProperties reviewProperties,
                                     ObjectMapper objectMapper,
                                     @Value("${DEEPSEEK_API_KEY:}") String apiKey) {
        this.llmProperties = llmProperties;
        this.reviewProperties = reviewProperties;
        this.objectMapper = objectMapper;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
    }

    @Override
    public PostprocessResult process(PostprocessCommand command) {
        if (apiKey.isEmpty()) {
            return PostprocessResult.unavailable("EMPTY_KEY", ENGINE, llmProperties.getModel(),
                    command.promptVersion(), 0);
        }
        long startedAt = System.nanoTime();
        HttpURLConnection connection = null;
        try {
            connection = open(command);
            int status = connection.getResponseCode();
            if (status == 401 || status == 403) {
                return fail(command, "UNAUTHORIZED", startedAt, status);
            }
            if (status < 200 || status >= 300) {
                return fail(command, "HTTP_ERROR", startedAt, status);
            }
            String body = readBody(connection.getInputStream());
            int latencyMs = elapsedMs(startedAt);
            if (latencyMs > reviewProperties.getTimeoutMs()) {
                return fail(command, "TIMEOUT", startedAt, status);
            }
            PostprocessResult result = parse(command, body, latencyMs);
            if (result.ok()) {
                log.info("review llm ok recordId={} decision={} engine={} latencyMs={} promptTokens={} completionTokens={} model={}",
                        command.recordId(), result.decision(), result.engine(), result.latencyMs(),
                        result.promptTokens(), result.completionTokens(), result.model());
            } else {
                log.warn("review llm unavailable recordId={} reason={} latencyMs={} httpStatus={}",
                        command.recordId(), result.errorReason(), result.latencyMs(), status);
            }
            return result;
        } catch (SocketTimeoutException e) {
            return fail(command, "TIMEOUT", startedAt, -1);
        } catch (IOException e) {
            return fail(command, "HTTP_ERROR", startedAt, -1);
        } catch (RuntimeException e) {
            return fail(command, "HTTP_ERROR", startedAt, -1);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private HttpURLConnection open(PostprocessCommand command) throws IOException {
        URL url = new URL(stripTrailingSlash(llmProperties.getBaseUrl()) + "/chat/completions");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(llmProperties.getConnectTimeoutMs());
        connection.setReadTimeout(reviewProperties.getTimeoutMs());
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Authorization", "Bearer " + apiKey);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", llmProperties.getModel());
        payload.put("messages", PostprocessPrompt.messages(command));
        payload.put("temperature", 0.0);
        payload.put("max_tokens", reviewProperties.getMaxTokens());
        payload.put("stream", false);
        payload.put("response_format", Map.of("type", "json_object"));
        byte[] body = objectMapper.writeValueAsBytes(payload);
        try (OutputStream out = connection.getOutputStream()) {
            out.write(body);
        }
        return connection;
    }

    private PostprocessResult parse(PostprocessCommand command, String body, int latencyMs) {
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (IOException e) {
            return PostprocessResult.unavailable("NON_JSON", ENGINE, llmProperties.getModel(),
                    command.promptVersion(), latencyMs);
        }
        JsonNode content = root.path("choices").path(0).path("message").path("content");
        if (!content.isTextual() || content.asText().isBlank()) {
            return PostprocessResult.unavailable("NON_JSON", ENGINE, llmProperties.getModel(),
                    command.promptVersion(), latencyMs);
        }
        JsonNode json;
        try {
            json = objectMapper.readTree(stripCodeFence(content.asText().trim()));
        } catch (IOException e) {
            return PostprocessResult.unavailable("NON_JSON", ENGINE, llmProperties.getModel(),
                    command.promptVersion(), latencyMs);
        }
        String decisionText = json.path("decision").asText("").trim().toUpperCase();
        PostprocessResult.Decision decision;
        if ("APPROVE".equals(decisionText)) {
            decision = PostprocessResult.Decision.APPROVE;
        } else if ("REJECT".equals(decisionText)) {
            decision = PostprocessResult.Decision.REJECT;
        } else {
            return PostprocessResult.unavailable("INVALID_DECISION", ENGINE, llmProperties.getModel(),
                    command.promptVersion(), latencyMs);
        }

        String title = textOrNull(json, "title");
        String description = textOrNull(json, "description");
        String location = textOrNull(json, "location");
        String summary = textOrNull(json, "summary");
        if (decision == PostprocessResult.Decision.APPROVE
                && (title == null || description == null || location == null || summary == null)) {
            return PostprocessResult.unavailable("INCOMPLETE_OUTPUT", ENGINE, llmProperties.getModel(),
                    command.promptVersion(), latencyMs);
        }

        List<String> reasons = reasons(json.path("reasons"));
        Integer promptTokens = intOrNull(root.path("usage").path("prompt_tokens"));
        Integer completionTokens = intOrNull(root.path("usage").path("completion_tokens"));
        return PostprocessResult.ok(decision, title, description, location, summary, reasons, ENGINE,
                llmProperties.getModel(), command.promptVersion(), latencyMs, promptTokens, completionTokens);
    }

    private static List<String> reasons(JsonNode node) {
        List<String> reasons = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode item : node) {
                if (item.isTextual() && !item.asText().isBlank()) {
                    String text = item.asText().strip();
                    if (text.length() > REASON_MAX_LENGTH) {
                        text = text.substring(0, REASON_MAX_LENGTH);
                    }
                    reasons.add(text);
                    if (reasons.size() == 3) {
                        break;
                    }
                }
            }
        } else if (node != null && node.isTextual() && !node.asText().isBlank()) {
            reasons.add(node.asText().strip());
        }
        return List.copyOf(reasons);
    }

    private static String textOrNull(JsonNode json, String field) {
        JsonNode node = json.get(field);
        return node != null && node.isTextual() && !node.asText().isBlank() ? node.asText().strip() : null;
    }

    private PostprocessResult fail(PostprocessCommand command, String reason, long startedAt, int status) {
        int latencyMs = elapsedMs(startedAt);
        log.warn("review llm unavailable recordId={} reason={} latencyMs={} httpStatus={}",
                command.recordId(), reason, latencyMs, status);
        return PostprocessResult.unavailable(reason, ENGINE, llmProperties.getModel(),
                command.promptVersion(), latencyMs);
    }

    private static int elapsedMs(long startedAt) {
        return (int) ((System.nanoTime() - startedAt) / 1_000_000L);
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

    private static String readBody(InputStream stream) throws IOException {
        try (InputStream in = stream) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
