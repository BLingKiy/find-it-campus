/**
 * 匹配引擎抽象层：接口与类型，<b>无实现</b>（实现在 {@code engine.local} / {@code engine.remote}，属 3.2）。
 *
 * <p>它存在的唯一理由是"换实现不换调用方"：本地关键词与外部 embedding 通过
 * {@code findit.match.engine} 一个配置值切换（ADR-009），且不做自动回退。
 */
package com.findit.campus.match.engine;
