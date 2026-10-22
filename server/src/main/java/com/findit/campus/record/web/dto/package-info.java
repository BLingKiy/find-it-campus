/**
 * record 的出入参 DTO（与 {@code docs/api-contract.md} §3–§6 的字段名一一对应）。
 *
 * <p>为什么 DTO 放在 {@code web} 包里而不是独立 {@code dto} 包：它们只对 HTTP 这一处消费者负责，
 * 跟着 controller 走才不会被人拿去当"通用模型"到处传（§6：实体不出 web 边界，DTO 也不进 service 之下）。
 *
 * <p>改名必须同步改契约文档 —— 这是刻意的耦合：契约漂移在编译期看不见，只能靠这个约定兜住。
 */
package com.findit.campus.record.web.dto;
