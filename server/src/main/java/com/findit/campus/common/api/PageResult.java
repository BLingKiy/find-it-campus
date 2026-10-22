package com.findit.campus.common.api;

import java.util.List;

/**
 * 分页结果外壳。
 *
 * <p>字段口径见 {@code docs/api-contract.md} §1：{@code page/pageSize/total/totalPages/list/hasNext}。
 * 不用 offset/limit/pageNum（契约已拍板，改这里就要改前端）。
 *
 * <p>{@code total=0} 时 {@code totalPages=0}、{@code list=[]}、{@code hasNext=false}，
 * 由构造函数算出，不让调用方拼半截状态。
 *
 * @param <T> 列表元素类型（DTO，不是实体 —— 实体不出 web 边界，见 §6）
 */
public final class PageResult<T> {

    private final int page;
    private final int pageSize;
    private final long total;
    private final List<T> list;

    private PageResult(int page, int pageSize, long total, List<T> list) {
        this.page = page;
        this.pageSize = pageSize;
        this.total = total;
        this.list = list;
    }

    /** 由总数与分页参数构造；{@code list} 必须是已取回的当前页数据。 */
    public static <T> PageResult<T> of(int page, int pageSize, long total, List<T> list) {
        return new PageResult<>(page, pageSize, total, list == null ? List.of() : List.copyOf(list));
    }

    /** 下列 getter 命名决定 JSON 字段名（{@code page/pageSize/total/totalPages/list/hasNext}），不可改。 */
    public int getPage() {
        return page;
    }

    public int getPageSize() {
        return pageSize;
    }

    public long getTotal() {
        return total;
    }

    public List<T> getList() {
        return list;
    }

    public int getTotalPages() {
        if (total <= 0 || pageSize <= 0) {
            return 0;
        }
        return (int) Math.ceil((double) total / pageSize);
    }

    public boolean getHasNext() {
        return page < getTotalPages();
    }
}
