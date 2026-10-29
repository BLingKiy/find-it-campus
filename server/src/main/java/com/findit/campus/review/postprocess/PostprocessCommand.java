package com.findit.campus.review.postprocess;

import java.time.LocalDateTime;

import com.findit.campus.record.domain.RecordType;

/**
 * 一次发布后处理/审核调用的输入。
 *
 * <p>只带<b>已过身份剥离</b>的文本（调用方保证），不带 contact/publisherName/publisherId：
 * 类型层面拿不到这些字段，"身份信息不出服务器"就不依赖调用方记得删。
 */
public record PostprocessCommand(
        long recordId,
        RecordType type,
        String categoryName,
        String title,
        String description,
        String location,
        LocalDateTime occurredAt,
        String promptVersion) {
}
