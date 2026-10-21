/**
 * 物品分类字典（分类上下文）。<b>{@code t_category} 的唯一写方</b>。
 *
 * <p>为什么独立成表而不是 ENUM 列：调整分类不该要求改 DDL 并迁移全表数据（ADR-002）。
 * record 与 match 都是只读消费方，只引用 {@code id}。
 */
package com.findit.campus.category;
