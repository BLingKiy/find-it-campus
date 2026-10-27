/**
 * pair 上下文（配对分数、逐端授权/确认、记录终态可见性口径）。
 *
 * <p>C1 只落这个上下文的**只读**部分：{@link com.findit.campus.pair.config.PairPolicyProperties}
 * （阈值与保留期唯一配置）和 pair 事实查询适配器；pair 的写路径（PATCH pair、取号、授权）
 * 属于 E1，背景打分与扫描属于 D1。接口在 record 侧（{@code record.service.PairFactsPort}），
 * 实现放在这里，保证记录上下文不直接写 {@code t_pair_score}。
 */
package com.findit.campus.pair;
