package com.findit.campus.pair.engine.local;

import java.util.List;

import com.findit.campus.category.domain.Category;
import com.findit.campus.category.mapper.CategoryMapper;

/**
 * {@link CategoryMapper} 的空实现：只在"容器里没有类目 Mapper"时兜底用。
 *
 * <p>什么情况会没有：{@code KeywordMatchEngine} 依赖的是 {@code @Lazy} 代理，正常运行时一定有；
 * 但在<b>纯单元测试</b>（不启 Spring、不连库）里没有 Mapper 可用。降级打分器不应该因为
 * "拿不到类目词表"就先抛 NPE —— 词表只是一个加权信号，空词表下关键词覆盖照样能算分。
 *
 * <p>不返回 {@code null}：{@code KeywordMatchEngine#loadLexicon} 对空列表的处理是"空词表"，
 * 对 {@code null} 的处理是 NPE。两者差别很大，这里必须是空列表。
 */
final class EmptyCategoryMapper implements CategoryMapper {

    @Override
    public List<Category> selectAll(boolean onlyActive) {
        return List.of();
    }

    @Override
    public Category selectById(long id) {
        return null;
    }
}
