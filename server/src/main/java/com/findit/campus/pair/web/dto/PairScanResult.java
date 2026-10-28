package com.findit.campus.pair.web.dto;

import java.util.Map;

/**
 * 一次兜底扫描的结果（契约 §4.18 + 架构 §5.4 的输出口径）。
 *
 * <p>字段就是"这次到底做了什么"的最小集合：
 * <ul>
 *   <li>{@code scanned}：取到的待办记录数（受 {@code scan-limit} 限制）</li>
 *   <li>{@code rescored}：真正写入/更新了多少行 pair（<b>不含</b>跳过的）</li>
 *   <li>{@code degraded}：本轮结束时处于降级状态的记录数</li>
 *   <li>{@code skipped}：因哈希未变或拿不到锁而跳过的 pair 数</li>
 *   <li>{@code expired}：已过保留期的 COMPLETED 记录数（只计数，不改写任何列）</li>
 *   <li>{@code limited}：是否因达到 {@code max-llm-calls}/批量上限提前停止（true = 还有积压）</li>
 *   <li>{@code elapsedMs}：本轮耗时</li>
 *   <li>{@code scope}：{@code FULL}（每日全量/手动兜底）或 {@code DEGRADED}（降级重扫）</li>
 *   <li>{@code skipReason}：本轮整体跳过的原因（{@code CIRCUIT_OPEN}），正常跑完为 {@code null}</li>
 * </ul>
 *
 * <p>为什么把 {@code scope} / {@code skipReason} 放进响应而不是只写日志：降级重扫在熔断打开时
 * 会<b>一条也不碰</b>（这是防额度风暴的设计），如果响应里只有 {@code scanned=0}，运维会误以为
 * "没有降级记录"。把跳过原因显式返回，才能区分"没活干"和"熔断挡着"。
 *
 * <p>{@code counters} 是进程内计数器快照（{@code llmCalls} 等）：它让"重复触发不重复调 LLM"
 * 这件事在没有外部仪表盘时也能被直接读数验证。它不持久化，进程重启归零。
 *
 * <p><b>不含任何正文/号码</b>：运维接口也是出口，脱敏口径与业务接口一致。
 */
public record PairScanResult(
        int scanned,
        int rescored,
        int degraded,
        int skipped,
        long expired,
        boolean limited,
        long elapsedMs,
        String trigger,
        String scope,
        String skipReason,
        Map<String, Object> counters) {

    /** 供响应体直接用：字段顺序稳定，便于证据文件里逐行核对。 */
    public static PairScanResult of(int scanned, int rescored, int degraded, int skipped, long expired,
                                    boolean limited, long elapsedMs, String trigger,
                                    String scope, String skipReason,
                                    Map<String, Object> counters) {
        return new PairScanResult(scanned, rescored, degraded, skipped, expired, limited, elapsedMs,
                trigger, scope, skipReason, counters);
    }

    /** 本轮整体跳过（例如熔断打开）：不扫任何记录，但把原因明确交给运维。 */
    public static PairScanResult skipped(String trigger, String scope, String skipReason,
                                         long elapsedMs, Map<String, Object> counters) {
        return of(0, 0, 0, 0, -1L, false, elapsedMs, trigger, scope, skipReason, counters);
    }
}
