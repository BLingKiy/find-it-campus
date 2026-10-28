package com.findit.campus.pair.config;

import java.util.concurrent.ThreadPoolExecutor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import com.findit.campus.pair.engine.PairScoringMetrics;

/**
 * 配对链路的异步执行器 + 调度开关（架构 §5.1：发布链路<b>不等待</b>打分）。
 *
 * <p>三个参数决定这条链路的性格：
 * <ul>
 *   <li><b>线程数</b>（默认 2）：每对打分持有一条数据库连接做命名锁，因此线程数必须明显小于
 *       Hikari 的 {@code maximum-pool-size}(10)。2 个线程意味着最多 2 条连接被锁占用，
 *       剩余 8 条留给 HTTP 请求 —— 这是"发布 P99 不受打分影响"的结构性前提。</li>
 *   <li><b>有界队列</b>（默认 200）：无界队列会在突发发布时把内存和"排到天荒地老的任务"一起攒起来。</li>
 *   <li><b>拒绝策略 = 丢弃并记录</b>：这是唯一能让"发布接口不因打分失败而失败"的选择。
 *       若用 {@code CallerRunsPolicy}，队列满时发布线程会亲自跑一次打分（含 LLM 超时 20 秒），
 *       直接把 P99 打到 20 秒 —— 那比丢任务严重得多。丢掉的任务由每日扫描补。</li>
 * </ul>
 *
 * <p>为什么拒绝处理器<b>不抛异常</b>：{@code @TransactionalEventListener(AFTER_COMMIT)} 在提交后
 * 的同一条线程上被调用，这里抛出的异常会冒泡到 {@code RecordService} 的调用方，变成"发布 500"。
 * 提交已经成功了，用户却收到失败 —— 这正是必须避免的语义错乱。
 *
 * <p>为什么 {@code @EnableAsync}/{@code @EnableScheduling} 放在这里而不是启动类：
 * 它们只服务于配对链路（异步打分 + 每日兜底扫描）。放在启动类会让"这个应用有后台任务"
 * 变成一个全局事实，将来别人加第二个定时任务时就会默认复用这里的线程池。
 */
@Configuration(proxyBeanMethods = false)
@EnableAsync
@EnableScheduling
public class PairAsyncConfig {

    public static final String PAIR_SCORING_EXECUTOR = "pairScoringExecutor";

    private static final Logger log = LoggerFactory.getLogger(PairAsyncConfig.class);

    @Bean(name = PAIR_SCORING_EXECUTOR)
    public ThreadPoolTaskExecutor pairScoringExecutor(PairScoreProperties properties,
                                                      PairScoringMetrics metrics) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        int threads = properties.getWorkerThreads();
        executor.setCorePoolSize(threads);
        executor.setMaxPoolSize(threads);
        executor.setQueueCapacity(properties.getWorkerQueueCapacity());
        executor.setThreadNamePrefix("pair-score-");
        // 优雅关闭：让正在跑的 LLM 调用有时间收尾，不无限期挂住停机
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(25);
        executor.setRejectedExecutionHandler((Runnable task, ThreadPoolExecutor pool) -> {
            metrics.enqueueRejected();
            log.warn("pair.enqueue.rejected queueSize={} activeCount={}（丢弃本次打分，等待每日扫描补算）",
                    pool.getQueue().size(), pool.getActiveCount());
        });
        executor.initialize();
        return executor;
    }
}
