package com.findit.campus.pair.domain;

/**
 * pair 的<b>一端</b>：LOST（寻物启事）或 FOUND（招领启事）。
 *
 * <p>为什么用枚举而不是字符串：契约里 {@code viewerSide / targetSide / side} 三个字段都是这两个值，
 * 散着写字符串迟早出现 {@code "lost"} / {@code "LOST "} 这种只在线上才炸的取值。
 * 枚举同时给出 {@link #opposite()}，让「对方是哪一端」只有一处推导。
 */
public enum PairEnd {

    LOST,
    FOUND;

    public PairEnd opposite() {
        return this == LOST ? FOUND : LOST;
    }
}
