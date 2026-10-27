package com.findit.campus.pair.domain;

/**
 * {@code t_access_log.result} 的取值（DDL {@code ck_access_result} 同值域）。
 *
 * <p>审计要能回答「为什么没给号」，所以未授权不是一种结果，而是三种：
 * 对方没公开、对方没填号码、分数不够；再加越权与 pair 失效。
 */
public enum AccessResult {

    GRANTED,
    DENIED_NOT_PUBLIC,
    DENIED_NOT_PROVIDED,
    DENIED_LOW_SCORE,
    DENIED_NOT_OWNER,
    DENIED_PAIR_INVALID
}
