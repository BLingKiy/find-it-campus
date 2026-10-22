package com.findit.campus.category.web;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.findit.campus.category.service.CategoryService;
import com.findit.campus.category.web.dto.CategoryResponse;
import com.findit.campus.common.api.ApiResponse;

/**
 * {@code GET /api/categories} —— 分类字典（契约 §7）。
 *
 * <p>controller 只做三件事：绑参数、调 service、包 {@link ApiResponse}。
 * 不写业务规则、不注入 Mapper、不手填 code（§6 层职责）。
 */
@RestController
@RequestMapping("/api/categories")
public class CategoryController {

    private final CategoryService categoryService;

    public CategoryController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    /**
     * @param onlyActive 默认 {@code true}（列表筛选与提交页下拉同一口径）；
     *                   {@code false} 只在排查/演示"停用分类"行为时用
     */
    @GetMapping
    public ApiResponse<List<CategoryResponse>> list(
            @RequestParam(name = "onlyActive", required = false, defaultValue = "true") boolean onlyActive) {
        return ApiResponse.success(categoryService.list(onlyActive));
    }
}
