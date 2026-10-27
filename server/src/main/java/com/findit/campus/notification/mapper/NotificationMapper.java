package com.findit.campus.notification.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.findit.campus.notification.domain.Notification;

/**
 * {@code t_notification} 的读写出口。
 *
 * <p>C1 只交付"通知列表读取 + 输出遮蔽"；D1 追加以唯一键 {@code (user_id, pair_id, type)}
 * 为幂等键的<b>唯一</b>写入方法 {@link #insertIfAbsent}：同一对、同一类型、同一接收人只落一行。
 * 重复打分 / 重复扫描 / 发布与扫描同时到，都只会产生一条通知 —— 这是"不刷屏"的结构性保证，
 * 不靠调用方去重。
 *
 * <p>为什么没有 {@code update}/{@code delete}：通知是"已发生的投递事实"，记录关闭后也不回收
 * （契约 §4.16）。已读状态属 E1 的 {@code PATCH}，不在本条范围内。
 */
@Mapper
public interface NotificationMapper {

    long countByUser(@Param("userId") long userId);

    List<Notification> selectByUser(@Param("userId") long userId,
                                    @Param("offset") int offset,
                                    @Param("limit") int limit);

    /** 总未读数（不是本页）：契约 §4.16 顶栏红点用。 */
    long countUnreadByUser(@Param("userId") long userId);

    /**
     * 幂等写入：唯一键命中时什么都不改（{@code id = id} 是 MySQL 的 no-op 惯用法）。
     *
     * @return 1 = 新插入；0 = 已存在（不是错误）
     */
    int insertIfAbsent(Notification notification);
}
