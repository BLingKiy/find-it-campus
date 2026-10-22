/**
 * 记录的业务规则与事务边界（{@code @Transactional} 只标在写方法）。
 *
 * <p>状态流转表、排序字段白名单都在这一层。禁止 import HTTP 相关类型，也禁止把实体直接递给 controller。
 */
package com.findit.campus.record.service;
