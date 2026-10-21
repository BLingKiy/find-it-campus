package com.findit.campus.common.config;

import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * Jackson 全局口径（契约 §1：<b>时间 {@code yyyy-MM-dd HH:mm:ss}、本地时区、不带毫秒、不出时间戳</b>）。
 *
 * <p>为什么需要它：DTO 里的 {@code DATETIME} 默认是数字时间戳或带 {@code T}/毫秒的字符串，
 * 前端再自己转时区就会出现"差 8 小时"这类漂移；这里一次性锁死，所有 {@code LocalDateTime} 共用同一格式。
 *
 * <p>用自定义器而不是 {@code new ObjectMapper()}：保留了 Boot 对 {@code ObjectMapper} 的其他默认项
 * （模块自动注册、fail-on-unknown 等），只覆盖时间这一项，改动面最小。
 */
@Configuration(proxyBeanMethods = false)
public class JacksonConfig {

    public static final String DATE_TIME_PATTERN = "yyyy-MM-dd HH:mm:ss";
    public static final String TIME_ZONE = "Asia/Shanghai";

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer jacksonCustomizer() {
        return builder -> builder
                .simpleDateFormat(DATE_TIME_PATTERN)
                .timeZone(TIME_ZONE)
                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
}
