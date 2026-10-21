/**
 * 业务异常与全局异常处理 —— 失败的<b>唯一</b>出口。
 *
 * <p>controller 不允许 {@code try/catch} 之后自行决定错误码，也不允许返回除了统一响应体以外的形状。
 */
package com.findit.campus.common.error;
