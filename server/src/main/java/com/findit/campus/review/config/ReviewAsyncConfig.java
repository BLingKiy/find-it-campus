package com.findit.campus.review.config;

import java.util.concurrent.ThreadPoolExecutor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 发布闸门的异步执行器。
 *
 * <p>与配对池隔离：审核一次要 3–45 秒（含 LLM 与重试退避），若与打分共用 2 个线程，
 * 发布会把打分饿死、反之亦然。这里固定 2 个线程、队列 100 —— 审核是**发布延迟**的一部分，
 * 不能像打分那样"丢了等每日扫描"，因此容量要按发布峰值给，队列满时记录停在 PENDING，
 * 由 {@code ReviewReconcileJob} 兜底重投（安全侧：不上架，不是"漏审上架"）。
 *
 * <p>拒绝策略只记日志、不抛异常：{@code @TransactionalEventListener} 的调用线程是发布请求线程，
 * 抛异常会把"已经提交成功"的发布变成 500 —— 这是必须避免的语义错乱。
 */
@Configuration(proxyBeanMethods = false)
public class ReviewAsyncConfig {

    public static final String REVIEW_EXECUTOR = "reviewExecutor";

    private static final Logger log = LoggerFactory.getLogger(ReviewAsyncConfig.class);

    @Bean(name = REVIEW_EXECUTOR)
    public ThreadPoolTaskExecutor reviewExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("review-gate-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(50);
        executor.setRejectedExecutionHandler((Runnable task, ThreadPoolExecutor pool) ->
                log.warn("review.enqueue.rejected queueSize={} activeCount={}（记录保持 PENDING，等待兜底调度）",
                        pool.getQueue().size(), pool.getActiveCount()));
        executor.initialize();
        return executor;
    }
}
