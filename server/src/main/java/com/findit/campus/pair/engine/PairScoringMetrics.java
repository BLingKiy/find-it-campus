package com.findit.campus.pair.engine;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Component;

/**
 * 配对链路的进程内计数器（单实例演示形态的可观测性下限）。
 *
 * <p><b>为什么不是 Micrometer</b>：本项目的 pom 没有 {@code micrometer-core} / actuator，
 * 架构 §12.1 的指标名是"上线后接仪表盘"的契约，不是当前阶段的依赖。为了让 D1 的验收
 * （"LLM 调用计数不变"）在<b>没有外部仪表盘</b>时也能被证明，这里用原子计数器把六个关键数
 * 暴露成 {@link #snapshot()}，由 {@code POST /api/ops/pair-scan} 的响应与日志输出。
 *
 * <p>这些数<b>不持久化</b>：进程重启归零，因此它们是"本次进程运行期"的观测值，
 * 不能当业务数据用。真正的业务事实在 {@code t_pair_score}（engine / degraded_reason / updated_at）。
 *
 * <p>禁止把 {@code userId}/{@code pairId} 变成计数键：那是高基数标签，会把内存打爆，
 * 对排查也没有额外价值（日志里已经有 id）。
 */
@Component
public class PairScoringMetrics {

    private final AtomicLong llmCalls = new AtomicLong();
    private final AtomicLong llmSuccess = new AtomicLong();
    private final AtomicLong degraded = new AtomicLong();
    private final AtomicLong skippedUnchanged = new AtomicLong();
    private final AtomicLong pairsWritten = new AtomicLong();
    private final AtomicLong enqueueRejected = new AtomicLong();
    private final AtomicLong lockMisses = new AtomicLong();
    private final Map<String, AtomicLong> degradedByReason = new LinkedHashMap<>();

    public PairScoringMetrics() {
        for (DegradedReason reason : DegradedReason.values()) {
            degradedByReason.put(reason.name(), new AtomicLong());
        }
    }

    public void llmCallStarted() {
        llmCalls.incrementAndGet();
    }

    public void llmSucceeded() {
        llmSuccess.incrementAndGet();
    }

    public void degraded(DegradedReason reason) {
        degraded.incrementAndGet();
        degradedByReason.computeIfAbsent(reason.name(), key -> new AtomicLong()).incrementAndGet();
    }

    public void skippedUnchanged() {
        skippedUnchanged.incrementAndGet();
    }

    public void pairWritten() {
        pairsWritten.incrementAndGet();
    }

    public void enqueueRejected() {
        enqueueRejected.incrementAndGet();
    }

    public void lockMiss() {
        lockMisses.incrementAndGet();
    }

    public long llmCalls() {
        return llmCalls.get();
    }

    public long llmSuccess() {
        return llmSuccess.get();
    }

    public long degradedCount() {
        return degraded.get();
    }

    public long skipped() {
        return skippedUnchanged.get();
    }

    /** 给运维响应/日志用的快照；键名即日志字段，改键名等于改观测口径。 */
    public Map<String, Object> snapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("llmCalls", llmCalls.get());
        snapshot.put("llmSuccess", llmSuccess.get());
        snapshot.put("degraded", degraded.get());
        snapshot.put("skippedUnchanged", skippedUnchanged.get());
        snapshot.put("pairsWritten", pairsWritten.get());
        snapshot.put("enqueueRejected", enqueueRejected.get());
        snapshot.put("lockMisses", lockMisses.get());
        Map<String, Long> reasons = new LinkedHashMap<>();
        degradedByReason.forEach((reason, counter) -> {
            if (counter.get() > 0) {
                reasons.put(reason, counter.get());
            }
        });
        snapshot.put("degradedByReason", reasons);
        return snapshot;
    }
}
