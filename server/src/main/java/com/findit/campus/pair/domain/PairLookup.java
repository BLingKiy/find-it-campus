package com.findit.campus.pair.domain;

import com.findit.campus.record.domain.Record;

/**
 * 按 pairId 读到的三行事实：pair 本身 + 两端记录。
 *
 * <p>{@code lost/found} 为 {@code null} 只有两种可能：pair 不存在（pair 也为 null），
 * 或该端记录已被逻辑删除（{@code RecordMapper.selectById} 恒带 {@code deleted_at IS NULL}）。
 * 这两种情况在契约里都走 404/20004，但审计结果不同，因此保留「记录缺失」这个可判断的状态。
 */
public record PairLookup(PairScore pair, Record lost, Record found) {

    public boolean pairExists() {
        return pair != null;
    }

    public boolean recordsDeleted() {
        return pair != null && (lost == null || found == null);
    }

    public PairAccess access(long viewerId) {
        return new PairAccess(pair, lost, found, viewerId);
    }
}
