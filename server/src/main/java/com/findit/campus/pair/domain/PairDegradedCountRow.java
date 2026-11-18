package com.findit.campus.pair.domain;

/**
 * 「某条记录还有多少降级 pair」的批量计数行（P1-5）。
 *
 * <p>用一条 GROUP BY 查询覆盖一条记录及其所有对端，避免逐条记录再查一次 COUNT（N+1）。
 * 计数口径与 {@code PairFactsMapper} 的 MATCHABLE 谓词一致：只数两端仍可见、未 CLOSED、
 * COMPLETED 未过保留期的 pair。失效对端上的本地分不再把记录钉死在 DEGRADED。
 */
public class PairDegradedCountRow {

    private Long recordId;
    private Integer degradedCount;

    public Long getRecordId() {
        return recordId;
    }

    public void setRecordId(Long recordId) {
        this.recordId = recordId;
    }

    public Integer getDegradedCount() {
        return degradedCount;
    }

    public void setDegradedCount(Integer degradedCount) {
        this.degradedCount = degradedCount;
    }
}
