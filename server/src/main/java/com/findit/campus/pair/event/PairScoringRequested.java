package com.findit.campus.pair.event;

/**
 * "这条记录变了，请补打分"的领域事件。
 *
 * <p>为什么是事件而不是在 {@code RecordService} 里直接调打分服务：发布事务提交之前，
 * 读自己的新行才看得见（同一事务内可以，但那就把 LLM 调用拉进了事务边界）；
 * 提交之后，别的连接才看得到这条记录。事件 + {@code AFTER_COMMIT} 把"什么时候能读"
 * 这件事交给 Spring 的事务同步器，而不是靠调用方记住"要在 commit 之后"。
 *
 * <p>{@code requestId} 随事件一起带走：异步线程的 MDC 里没有它，日志就会断链，
 * 排障时无法把"发布 → 打分"串起来（架构 §12.3）。
 *
 * @param recordId 触发记录
 * @param trigger  {@code publish} / {@code update} / {@code scan} / {@code manual}，只进日志
 * @param requestId 触发请求的 requestId，可为 null（扫描/启动参数触发时没有 HTTP 请求）
 */
public record PairScoringRequested(long recordId, String trigger, String requestId) {
}
