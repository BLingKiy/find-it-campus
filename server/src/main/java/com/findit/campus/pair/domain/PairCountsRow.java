package com.findit.campus.pair.domain;

/**
 * 批量计数查询的一行（{@code MyBatis} 按 snake_case → camelCase 映射）。
 *
 * <p>用可变 bean 而不是 record：MyBatis 对 record 的构造器映射需要额外配置，
 * 而这里的字段全部由 SQL 别名决定，保持最小依赖。
 */
public class PairCountsRow {

    private Long recordId;
    private int total;
    private int related;
    private int lowConfidence;
    private int matched;

    public Long getRecordId() {
        return recordId;
    }

    public void setRecordId(Long recordId) {
        this.recordId = recordId;
    }

    public int getTotal() {
        return total;
    }

    public void setTotal(int total) {
        this.total = total;
    }

    public int getRelated() {
        return related;
    }

    public void setRelated(int related) {
        this.related = related;
    }

    public int getLowConfidence() {
        return lowConfidence;
    }

    public void setLowConfidence(int lowConfidence) {
        this.lowConfidence = lowConfidence;
    }

    public int getMatched() {
        return matched;
    }

    public void setMatched(int matched) {
        this.matched = matched;
    }
}
