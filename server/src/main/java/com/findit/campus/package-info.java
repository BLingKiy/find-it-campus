/**
 * 根包：find-it-campus 后端。
 *
 * <p>按能力分包，层在能力内部（ADR-003）：</p>
 * <pre>
 * com.findit.campus
 * ├─ common       跨能力的横切设施：统一响应体、错误码、异常处理、装配配置
 * ├─ record       失物/招领记录（t_record 唯一写方）
 * ├─ category     分类字典（t_category 唯一写方）
 * └─ match        语义匹配（t_match_result 唯一写方）
 * </pre>
 *
 * <p>依赖方向（不得反向）：{@code web → service → {engine, mapper} → domain}；
 * {@code common} 不 import 任何能力包。详见 {@code docs/architecture-baseline.md} §3 / §6。</p>
 */
package com.findit.campus;
