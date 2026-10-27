/**
 * 降级/本地关键词打分（{@code LOCAL_KEYWORD}）。
 *
 * <p>它存在的唯一目的：当 DeepSeek 不可用时，仍然给每条 pair 写一个 [0,1] 的确定性分数，
 * 让 {@code /my} 的条数与三档判定不至于因为"外部服务挂了"而消失。
 * 它<b>不是</b>一个可配置的"匹配引擎选项"—— 选择它只发生在降级路径上。
 */
package com.findit.campus.pair.engine.local;
