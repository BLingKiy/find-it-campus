/**
 * 本地关键词实现（{@code LOCAL_KEYWORD}）：3.2 交付，默认配置值 {@code local}。
 *
 * <p>实现要点预告：候选池 + 内存打分，<b>不依赖 SQL 全文检索</b> —— 中文 ngram 索引在部分环境不可用，
 * 这条路径要能独立工作（ADR-008 的容错点）。
 */
package com.findit.campus.match.engine.local;
