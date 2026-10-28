package com.findit.campus.record.domain;

/**
 * 一条记录的有效 pair 计数（契约 §4.12 的 pairCount / relatedCount / lowConfidenceCount / matchedPairCount）。
 *
 * <p>「有效」= 对端记录未删除、且未 CLOSED/未过保留期；<b>不含</b>任一端失效的 pair 行。
 * 三个计数共用同一份 SQL 聚合结果，避免"卡片数 1、另说低分 2 条、总数 4"这种自己跟自己打架的界面。
 *
 * @param total       有效 pair 行数（含低于解锁阈值的）
 * @param related     {@code score ≥ contact_unlock_threshold} 且 pairUsable 的条数（卡片数）
 * @param lowConfidence 低于解锁阈值的条数（只给条数，不返回卡片）
 * @param matched     有效 MATCHED pair 数（决定「标记完成」是否可点）
 */
public record MyPairCounts(int total, int related, int lowConfidence, int matched) {

    public static final MyPairCounts EMPTY = new MyPairCounts(0, 0, 0, 0);
}
