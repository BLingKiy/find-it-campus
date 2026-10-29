package com.findit.campus.review.event;

/**
 * 发布/编辑事务提交后触发一次异步审核（{@code @TransactionalEventListener(AFTER_COMMIT)}）。
 *
 * <p>为什么用事件而不是在 service 里直接调：审核要打 LLM（秒级），而 {@code POST /api/records}
 * 的契约是同步返回 200 + id。事件把"事务提交"与"审核开始"隔开：提交前记录对别人不可见，
 * 提交后审核线程才读得到它；即使审核队列满/进程重启，记录也只会停在 PENDING（安全侧）。
 */
public record RecordSubmittedForReview(long recordId, String trigger, String requestId) {
}
