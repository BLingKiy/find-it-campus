package com.findit.campus.review.postprocess;

import java.util.List;

/**
 * 一次后处理/审核的结果。{@code status=OK} 不代表通过，只代表"模型返回了可解析的完整对象"；
 * 是否上架由 {@code decision} + 规则双检共同决定。
 *
 * <p>{@code errorReason} 是**可落库、可对本人展示**的短原因（如 {@code TIMEOUT}、
 * {@code NON_JSON}、{@code EMPTY_KEY}），不含 prompt/响应原文，也不含密钥。
 */
public record PostprocessResult(
        Status status,
        Decision decision,
        String title,
        String description,
        String location,
        String summary,
        List<String> reasons,
        String engine,
        String model,
        String promptVersion,
        Integer latencyMs,
        Integer promptTokens,
        Integer completionTokens,
        String errorReason) {

    public enum Status {
        OK,
        UNAVAILABLE
    }

    public enum Decision {
        APPROVE,
        REJECT
    }

    public static PostprocessResult ok(Decision decision, String title, String description, String location,
                                       String summary, List<String> reasons, String engine, String model,
                                       String promptVersion, Integer latencyMs,
                                       Integer promptTokens, Integer completionTokens) {
        return new PostprocessResult(Status.OK, decision, title, description, location, summary, reasons,
                engine, model, promptVersion, latencyMs, promptTokens, completionTokens, null);
    }

    public static PostprocessResult unavailable(String errorReason, String engine, String model,
                                                String promptVersion, Integer latencyMs) {
        return new PostprocessResult(Status.UNAVAILABLE, null, null, null, null, null, List.of(),
                engine, model, promptVersion, latencyMs, null, null, errorReason);
    }

    public boolean ok() {
        return status == Status.OK;
    }

    /** usage 缺失时返回 {@code null}：不估算（与 pair 打分同一口径）。 */
    public Integer totalTokensOrNull() {
        if (promptTokens == null && completionTokens == null) {
            return null;
        }
        return (promptTokens == null ? 0 : promptTokens) + (completionTokens == null ? 0 : completionTokens);
    }
}
