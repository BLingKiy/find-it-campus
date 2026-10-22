package com.findit.campus.record.domain;

/**
 * 匹配候选池的一行（id + 参与打分的文本 + 加权用字段）。
 *
 * <p>与 {@link RecordSummary} 同属"读模型"，刻意只取引擎真正需要的列：
 * 候选池上限 500 行，把 {@code contact}/{@code publisher_name} 也捞出来只是给内存和日志增加
 * 不必要的个人信息副本（契约 §1：联系方式只在详情给）。
 *
 * <p>为什么这里不直接返回 {@code match.engine.MatchCandidateSource}：依赖方向铁律要求
 * {@code record ✗→ match}（§3）。类型转换由 match 侧的适配器做，record 侧不认识引擎的类型。
 */
public class RecordCandidate {

    private Long id;
    private String title;
    private String description;
    private Long categoryId;
    private String location;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Long getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(Long categoryId) {
        this.categoryId = categoryId;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }
}
