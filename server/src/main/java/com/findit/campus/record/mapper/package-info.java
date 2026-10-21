/**
 * 记录的数据访问：接口 + {@code resources/mapper/record/RecordMapper.xml}。
 *
 * <p>允许单表 CRUD 与显式分页 SQL；<b>禁止用 {@code ${}} 拼接任何来自请求的值</b>（ADR-014，注入面）。
 * XML 位置见 {@code application.yml} 的 {@code mybatis.mapper-locations}。
 */
package com.findit.campus.record.mapper;
