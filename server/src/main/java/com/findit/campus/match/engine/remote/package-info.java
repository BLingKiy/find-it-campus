/**
 * 外部 embedding 实现（{@code EXTERNAL_EMBEDDING}）：3.2 交付，配置值 {@code external}。
 *
 * <p>HTTP 细节（超时、Key、错误映射为 30002/30003）全部挡在这一层，不泄漏到 service。
 * 密钥走环境变量 {@code FINDIT_EMBED_API_KEY}，不进仓库。
 */
package com.findit.campus.match.engine.remote;
