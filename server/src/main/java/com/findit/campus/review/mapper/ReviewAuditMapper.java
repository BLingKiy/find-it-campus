package com.findit.campus.review.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.findit.campus.review.domain.ReviewAudit;

/** {@code t_review_audit} 的唯一写入口：只插不改不删。 */
@Mapper
public interface ReviewAuditMapper {

    int insert(ReviewAudit audit);

    List<ReviewAudit> selectByRecordId(@Param("recordId") long recordId, @Param("limit") int limit);
}
