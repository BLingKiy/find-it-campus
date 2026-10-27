package com.findit.campus.pair.mapper;

import org.apache.ibatis.annotations.Mapper;

import com.findit.campus.pair.domain.AccessLog;

/**
 * {@code t_access_log} 的唯一写出口：只有 INSERT，没有 UPDATE / DELETE。
 *
 * <p>append-only 不是口头约定：审计表一旦可改，「谁在何时看过号码」就不再是可信证据。
 * 需要清理只能走数据保留流程（v2 范围外），不在应用层提供接口。
 */
@Mapper
public interface AccessLogMapper {

    int insert(AccessLog accessLog);
}
