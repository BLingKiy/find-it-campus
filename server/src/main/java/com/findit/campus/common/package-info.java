/**
 * 跨能力的横切设施：统一响应体、错误码、异常处理与装配配置。
 *
 * <p><b>唯一允许被所有能力 import 的包</b>，反过来它不许 import 任何能力包
 * （否则 common 会变成隐式共享模型，改一处爆一片）。
 *
 * <p>为什么 health 也在这里（{@code common.web}）：它不是某个业务能力的接口，而是进程级探针。
 * 这是相对 {@code docs/architecture-baseline.md} §5.2 的一处补充（基线未在 common 下列出 web 子包），特此登记。
 */
package com.findit.campus.common;
