package com.findit.campus.common.config;

import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * 匹配引擎装配点（ADR-009：配置切换 + {@code @ConditionalOnProperty}，<b>不做自动回退</b>）。
 *
 * <p>骨架阶段这里只做两件事：</p>
 * <ol>
 *   <li>确认 {@code findit.match.engine} 的取值合法 —— 不合法就直接让启动失败，不要带病运行（§9.2）</li>
 *   <li>把两个实现该挂在哪个 {@code @ConditionalOnProperty} 下写清楚，3.2 交付实现类时照抄即可</li>
 * </ol>
 *
 * <pre>{@code
 * // 装配点一：本地关键词实现（默认）
 * @Bean
 * @ConditionalOnProperty(name = "findit.match.engine", havingValue = "local", matchIfMissing = true)
 * public MatchEngine keywordMatchEngine(KeywordMatchEngine engine) { return engine; }
 *
 * // 装配点二：外部 embedding 实现
 * @Bean
 * @ConditionalOnProperty(name = "findit.match.engine", havingValue = "external")
 * public MatchEngine externalEmbeddingMatchEngine(ExternalEmbeddingMatchEngine engine) { return engine; }
 * }</pre>
 *
 * <p>为什么两个实现不能同时存在也不要自动回退：一旦外部挂了就悄悄用关键词分数顶上，
 * 用户以为拿到的是"语义匹配"结果，5.1 的评估数据就被污染了 —— 那是答辩口径上的诚信问题。
 * 外部不可用时应该明确返回 30002/503。</p>
 */
@Configuration(proxyBeanMethods = false)
public class MatchEngineConfig {

    public static final String ENGINE_PROPERTY = "findit.match.engine";
    public static final Set<String> ENGINE_VALUES = Set.of("local", "external");

    private final String engine;

    public MatchEngineConfig(@Value("${" + ENGINE_PROPERTY + ":local}") String engine) {
        if (!ENGINE_VALUES.contains(engine)) {
            throw new IllegalStateException(
                    ENGINE_PROPERTY + " 取值非法: " + engine + "，可选值 " + ENGINE_VALUES + "（见 §9.2 不要带病启动）");
        }
        this.engine = engine;
    }

    /** 当前装配的引擎标识，供健康检查与启动日志使用。 */
    public String engine() {
        return engine;
    }
}
