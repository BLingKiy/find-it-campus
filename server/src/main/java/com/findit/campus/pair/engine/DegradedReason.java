package com.findit.campus.pair.engine;

/**
 * 降级原因（{@code t_pair_score.degraded_reason} 的值域，与 DDL CHECK 一一对应）。
 *
 * <p>用枚举而不是字符串常量：拼错一个字母会被数据库 CHECK 拒掉并让整条链路变成 500，
 * 而这里拼错是编译错误。{@link #name()} 落库，因此<b>枚举名不得改</b>（改 = 改库值域）。
 *
 * <p>{@code EMPTY_KEY} 与其余几个的区别要记住：空 Key 是"没配"，根本不会发生网络调用；
 * 401/超时/非 JSON/越界/HTTP 错误是"配了但不通"。运维排障时这两类的处置完全不同
 * （补密钥 vs 查网络/额度），所以不能合并成一个 {@code ERROR}。
 */
public enum DegradedReason {

    /** 环境变量 {@code DEEPSEEK_API_KEY} 为空/空白：不发起任何网络请求。 */
    EMPTY_KEY,

    /** 401 / 403：密钥无效或权限不足。 */
    UNAUTHORIZED,

    /** connect / read / total 任一超时。 */
    TIMEOUT,

    /** 响应不是合法 JSON 对象，或缺 {@code choices[0].message.content}。 */
    NON_JSON,

    /** 分数不是数字、为 NaN、或不在 [0,1]。 */
    SCORE_OUT_OF_RANGE,

    /** 其他非 2xx（429 / 5xx 等）。 */
    HTTP_ERROR,

    /** 连续失败触发熔断，冷却期内直接本地分。 */
    CIRCUIT_OPEN,
    ;
}
