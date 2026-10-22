package com.findit.campus.category.service;

import java.util.List;

import org.springframework.stereotype.Service;

import com.findit.campus.category.domain.Category;
import com.findit.campus.category.mapper.CategoryMapper;
import com.findit.campus.category.web.dto.CategoryResponse;
import com.findit.campus.common.api.ErrorCode;
import com.findit.campus.common.error.BizException;

/**
 * 分类字典的读服务 + 分类合法性判定（后者是 record / match 都要用的共享规则）。
 *
 * <p>为什么把"校验 categoryId"放进分类上下文而不是让 record 自己查一遍：
 * 存在性与启用状态的定义（停用分类不可新选、但历史记录仍显示名称）属于分类这个上下文的知识，
 * 让 record 复制一遍 SQL 就等于把这条规则抄成两份 —— 停用口径一改就要改两处。
 */
@Service
public class CategoryService {

    private final CategoryMapper categoryMapper;

    public CategoryService(CategoryMapper categoryMapper) {
        this.categoryMapper = categoryMapper;
    }

    /**
     * 分类字典（契约 §7）。
     *
     * @param onlyActive 默认 {@code true}；传 {@code false} 时含停用分类
     */
    public List<CategoryResponse> list(boolean onlyActive) {
        return categoryMapper.selectAll(onlyActive).stream()
                .map(CategoryResponse::from)
                .toList();
    }

    /**
     * 校验"可以作为新记录分类"的 categoryId：不存在 → 40001，已停用 → 40002。
     *
     * <p>为什么提交必须查库而不信前端下拉：前端拿到字典后分类可能被停用，且接口对任何访客开放，
     * 下拉框的值不构成任何约束 —— 服务端校验才是唯一可信的那道。
     *
     * @param categoryId 可空；{@code null} 表示"不分类"，直接放行
     */
    public void requireSelectable(Long categoryId) {
        if (categoryId == null) {
            return;
        }
        Category category = categoryMapper.selectById(categoryId);
        if (category == null) {
            throw BizException.of(ErrorCode.CATEGORY_NOT_FOUND);
        }
        if (!Boolean.TRUE.equals(category.getActive())) {
            throw BizException.of(ErrorCode.CATEGORY_INACTIVE, "分类已停用不可选: " + category.getName());
        }
    }

    /**
     * 校验"存在即可"（匹配接口的口径，契约 §8.3：不存在 → 40001，但<b>不要求启用</b>）。
     *
     * <p>与提交的区别是有意保留的：匹配时用它做过滤/加权，不是"给用户选一个分类"，
     * 拿一个已停用分类去过滤历史记录是合法且有用的（历史记录仍属于该分类）。
     */
    public void requireExists(Long categoryId) {
        if (categoryId == null) {
            return;
        }
        if (categoryMapper.selectById(categoryId) == null) {
            throw BizException.of(ErrorCode.CATEGORY_NOT_FOUND);
        }
    }
}
