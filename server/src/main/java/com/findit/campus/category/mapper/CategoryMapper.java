package com.findit.campus.category.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.findit.campus.category.domain.Category;

/**
 * {@code t_category} 的只读出口。
 *
 * <p>这个上下文<b>不提供写接口</b>（契约里没有分类管理接口，分类由 DDL + seed 维护），
 * 因此这里只有 SELECT —— 接口少一个，就少一处"忘了停用校验"的可能。
 */
@Mapper
public interface CategoryMapper {

    /**
     * 分类字典。
     *
     * @param onlyActive {@code true} 只返回 {@code is_active=1}（默认口径：列表筛选与提交页下拉）
     * @return 按 {@code sort_order} 升序；同序时按 id 升序（顺序稳定，前端不需要再排）
     */
    List<Category> selectAll(@Param("onlyActive") boolean onlyActive);

    /**
     * 按 id 取分类（含停用的）—— 提交/筛选时要能区分"不存在"（40001）与"已停用"（40002），
     * 只查 active 的话这两种情况都会返回 null，错误码就给错了。
     */
    Category selectById(@Param("id") long id);
}
