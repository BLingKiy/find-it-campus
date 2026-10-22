/**
 * 跨能力复用的入参约束（契约口径的校验注解放这里，能力特有的放各自 {@code web/dto}）。
 *
 * <p>为什么单列一个包：注解 + 校验器是成对的，且会被 record/match 多个 DTO 引用；
 * 放在某个能力包里会让另一个能力反向依赖它（违反 §3 依赖方向）。
 * 只有确实被 ≥2 处用到的约束才进这里（见 {@code common/util} 的同一条纪律）。
 */
package com.findit.campus.common.validation;
