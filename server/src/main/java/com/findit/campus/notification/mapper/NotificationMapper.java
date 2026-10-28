package com.findit.campus.notification.mapper;

import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.findit.campus.notification.domain.Notification;

/**
 * {@code t_notification} 的读写出口。
 *
 * <p>C1 交付"通知列表读取 + 输出遮蔽"；D1 追加以唯一键 {@code (user_id, pair_id, type)}
 * 为幂等键的<b>唯一</b>写入方法 {@link #insertIfAbsent}：同一对、同一类型、同一接收人只落一行。
 * 重复打分 / 重复扫描 / 发布与扫描同时到，都只会产生一条通知 —— 这是"不刷屏"的结构性保证，
 * 不靠调用方去重。E1 追加 {@link #markRead}（只写 {@code read_at}，且只写一次）。
 *
 * <p>为什么没有 {@code delete}：通知是"已发生的投递事实"，记录关闭后也不回收（契约 §4.16）。
 * 唯一的 UPDATE 是已读时间，且 {@code read_at IS NULL} 保证重复标记不刷新第一次的时间。
 */
@Mapper
public interface NotificationMapper {

    long countByUser(@Param("userId") long userId);

    List<Notification> selectByUser(@Param("userId") long userId,
                                    @Param("offset") int offset,
                                    @Param("limit") int limit);

    /** 总未读数（不是本页）：契约 §4.16 顶栏红点用。 */
    long countUnreadByUser(@Param("userId") long userId);

    /** 按主键取一行；不存在返回 {@code null}（已读接口用它先判 404/403 顺序）。 */
    Notification selectById(@Param("id") long id);

    /**
     * 标记已读：{@code read_at IS NULL} 才写，返回 0 表示「已经是已读」或「并发已读」。
     *
     * <p>{@code user_id = #{userId}} 写进 WHERE 而不是先查后写：越权在 SQL 层就不成立，
     * 也不给「查与写之间换了人」留窗口。
     */
    int markRead(@Param("id") long id,
                 @Param("userId") long userId,
                 @Param("at") LocalDateTime at);

    /**
     * 幂等写入：唯一键命中时什么都不改（{@code id = id} 是 MySQL 的 no-op 惯用法）。
     *
     * @return 1 = 新插入；0 = 已存在（不是错误）
     */
    int insertIfAbsent(Notification notification);
}
