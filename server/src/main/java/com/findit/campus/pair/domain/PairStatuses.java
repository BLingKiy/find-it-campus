package com.findit.campus.pair.domain;

/**
 * {@code t_pair_score.status} 的取值（DDL {@code ck_pair_status} 同值域）。
 *
 * <p>不把实体字段改成枚举：MyBatis 的默认枚举映射对「数据库里出现未知值」是抛异常，
 * 而 pair 表是长期留存的证据表，未知值应该能被读出来排查，而不是让整个接口 500。
 * 这里只收敛常量，写路径仍只允许 E1 的服务调用。
 */
public final class PairStatuses {

    public static final String NEW = "NEW";
    public static final String CONTACTED = "CONTACTED";
    public static final String MATCHED = "MATCHED";
    public static final String IGNORED = "IGNORED";

    private PairStatuses() {
    }
}
