package com.findit.campus.common.config;

import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import com.findit.campus.category.mapper.CategoryMapper;
import com.findit.campus.match.engine.MatchEngine;
import com.findit.campus.match.engine.local.KeywordMatchEngine;

/**
 * 匹配引擎装配点（ADR-009：配置切换 + {@code @ConditionalOnProperty}，<b>不做自动回退</b>）。
 *
 * <p>这里做三件事：</p>
 * <ol>
 *   <li>确认 {@code findit.match.engine} 的取值合法 —— 不合法就直接让启动失败，不要带病运行（§9.2）</li>
 *   <li>把 {@code local} 实现注册成容器里<b>唯一的</b> {@link MatchEngine} bean（3.2 已落地）</li>
 *   <li>保留 {@code external} 的接入位置：实现类落地后照下面注释加一个同型 bean 方法即可，调用方零改动</li>
 * </ol>
 *
 * <pre>{@code
 * // 装配点二：外部 embedding 实现（3.2 不在范围内，接口与配置位已留好）
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

    /**
     * 本地关键词实现（默认）：容器里唯一的 {@link MatchEngine} bean。
     *
     * <p>为什么 {@code @Lazy} 注入 {@link CategoryMapper}：类目词表（{@code t_category.keywords}）只在
     * 第一次匹配时需要，用懒代理把"建 bean"和"连库"解耦 —— 数据库暂时不可用时应用仍能启动，
     * 由 {@code /api/health} 报 DOWN，而不是卡在启动期。词表本身由引擎缓存，不会每请求查库。
     *
     * <p>不注入候选池：3.1 的接口签名是 {@code match(candidates, query)}，候选由 {@code MatchService}
     * 递进来；引擎若能自己取候选，就又要把"哪些记录可见"的业务规则复制一遍（ADR-008）。
     */
    @Bean
    @ConditionalOnProperty(prefix = "findit.match", name = "engine",
            havingValue = "local", matchIfMissing = true)
    public MatchEngine keywordMatchEngine(@Lazy CategoryMapper categoryMapper) {
        return new KeywordMatchEngine(categoryMapper);
    }

    /** 当前装配的引擎标识，供健康检查与启动日志使用。 */
    public String engine() {
        return engine;
    }
}
