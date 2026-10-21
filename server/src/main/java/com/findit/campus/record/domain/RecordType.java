package com.findit.campus.record.domain;

/**
 * 记录类型：一张 {@code t_record} 承载两类启事（ADR-001），靠这个值区分。
 *
 * <p>对外 JSON 回枚举原值（{@code LOST}/{@code FOUND}），中文文案由前端集中映射 ——
 * 措辞改动不该要求后端发一次版本。
 */
public enum RecordType {

    /** 寻物启事：我丢了东西。 */
    LOST,
    /** 招领启事：我捡到东西。 */
    FOUND,
    ;

    /** 匹配候选池取的是<b>相反</b>类型：找 LOST 时从 FOUND 里挑候选，反之亦然。 */
    public RecordType opposite() {
        return this == LOST ? FOUND : LOST;
    }
}
