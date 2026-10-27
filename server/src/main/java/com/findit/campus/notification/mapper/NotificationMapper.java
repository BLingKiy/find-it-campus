package com.findit.campus.notification.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.findit.campus.notification.domain.Notification;

/**
 * {@code t_notification} 的只读出口（C1）。
 *
 * <p>C1 只交付"通知列表读取 + 输出遮蔽"这一个出口；通知的<b>产生</b>（PAIR_MATCH/PAIR_UNLOCK/SYSTEM）
 * 属于 D1 的配对链路。这里先只有 SELECT，避免 C1 顺手长出一套与 D1 不同的写入语义
 * （唯一键 {@code (user_id, pair_id, type)} 的重复抑制是 D1 的职责）。
 */
@Mapper
public interface NotificationMapper {

    long countByUser(@Param("userId") long userId);

    List<Notification> selectByUser(@Param("userId") long userId,
                                    @Param("offset") int offset,
                                    @Param("limit") int limit);

    /** 总未读数（不是本页）：契约 §4.16 顶栏红点用。 */
    long countUnreadByUser(@Param("userId") long userId);
}
