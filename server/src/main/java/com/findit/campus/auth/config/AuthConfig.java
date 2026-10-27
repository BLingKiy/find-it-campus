package com.findit.campus.auth.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 认证上下文的两个基础设施 Bean：口令哈希器与可注入的时钟。
 *
 * <p><b>为什么不上 {@code spring-boot-starter-security}</b>：那会把整条过滤器链、默认表单登录、
 * HTTP Basic 和 CSRF token 全带进来，与契约"不开放注册、不开放表单登录、v2 不引入 CSRF token"的口径冲突；
 * 仅仅需要 bcrypt 时引 {@code spring-security-crypto} 就够了（任务书也是这个建议）。
 *
 * <p><b>为什么 cost 写死 10</b>：契约要求 {@code cost ≥ 10}，而 seed 里的演示口令哈希正是 cost=10。
 * 调高会让已有哈希仍然可校验（bcrypt 的 cost 存在哈希里，校验不看 Bean 配置），但"新哈希"只在本项目
 * 没有建号路径的前提下不会产生 —— 也就是说这个值当前只影响"校验开销"，不改变已有数据。
 * 若将来要迁移到 12，正确做法是"校验通过后按新 cost 重哈希"，不是直接改这里（改了也不会重算旧哈希）。
 */
@Configuration(proxyBeanMethods = false)
public class AuthConfig {

    /** bcrypt cost：契约下限 10；沿用 seed 的取值，避免"文档写 10、实际跑 12"的口径漂移。 */
    public static final int BCRYPT_COST = 10;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(BCRYPT_COST);
    }

    /**
     * 时钟 Bean：只给登录失败计数窗口用。
     *
     * <p>为什么把它做成 Bean 而不是直接 {@code Instant.now()}：15 分钟窗口的过期行为必须有测试，
     * 而"等 15 分钟"的测试等于没有测试。注入时钟后，单测可以把时间推到窗口之外。
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
