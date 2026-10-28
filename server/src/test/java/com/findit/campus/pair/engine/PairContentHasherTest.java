package com.findit.campus.pair.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.findit.campus.pair.domain.PairSide;
import com.findit.campus.record.domain.RecordStatus;
import com.findit.campus.record.domain.RecordType;

/**
 * 幂等键的内容哈希（架构 §8.5）。
 *
 * <p>这些用例锁定的是<b>覆盖范围</b>，不是某个具体哈希值：只要改了 title/description/location/category
 * 就必须变；改 contact（不在 {@link PairSide} 里）不可能变。前者防止"改了内容却不重算"
 * 的静默错误，后者防止"改个称呼就计费一次"。
 */
@DisplayName("PairContentHasher：幂等键覆盖 title/description/location/category")
class PairContentHasherTest {

    private static PairSide side(String title, String description, String location, Long categoryId) {
        return new PairSide(1L, RecordType.LOST, RecordStatus.PENDING, null,
                title, description, location, categoryId, "证件", null, 10L);
    }

    @Test
    @DisplayName("同一内容两次哈希一致，且是 64 位小写 hex")
    void deterministic() {
        String first = PairContentHasher.hash(side("丢失校园卡", "在图书馆二楼丢的校园卡", "图书馆", 3L));
        String second = PairContentHasher.hash(side("丢失校园卡", "在图书馆二楼丢的校园卡", "图书馆", 3L));
        assertEquals(first, second);
        assertEquals(64, first.length());
        assertTrue(first.matches("[0-9a-f]{64}"), "小写 hex：" + first);
    }

    @Test
    @DisplayName("标题变化 → 哈希变化（触发重算的唯一依据）")
    void titleChange() {
        String base = PairContentHasher.hash(side("丢失校园卡", "描述", "图书馆", 3L));
        assertNotEquals(base, PairContentHasher.hash(side("丢失饭卡", "描述", "图书馆", 3L)));
    }

    @Test
    @DisplayName("描述 / 地点 / 分类任一变化 → 哈希变化")
    void descriptionLocationCategoryChange() {
        String base = PairContentHasher.hash(side("丢失校园卡", "描述A", "图书馆", 3L));
        assertNotEquals(base, PairContentHasher.hash(side("丢失校园卡", "描述B", "图书馆", 3L)));
        assertNotEquals(base, PairContentHasher.hash(side("丢失校园卡", "描述A", "食堂", 3L)));
        assertNotEquals(base, PairContentHasher.hash(side("丢失校园卡", "描述A", "图书馆", 4L)));
        assertNotEquals(base, PairContentHasher.hash(side("丢失校园卡", "描述A", "图书馆", null)));
    }

    @Test
    @DisplayName("首尾空白不算变化（trim 后再算）：避免复制粘贴带空格就重算一次")
    void trimsWhitespace() {
        String base = PairContentHasher.hash(side("丢失校园卡", "描述", "图书馆", 3L));
        assertEquals(base, PairContentHasher.hash(side("  丢失校园卡 ", " 描述 ", "\t图书馆\n", 3L)));
    }

    @Test
    @DisplayName("类型参与哈希：同一段文本作为 LOST 与 FOUND 不是同一个键")
    void typeIsPartOfKey() {
        PairSide lost = side("校园卡", "描述", "图书馆", 3L);
        PairSide found = new PairSide(1L, RecordType.FOUND, RecordStatus.PENDING, null,
                "校园卡", "描述", "图书馆", 3L, "证件", null, 10L);
        assertNotEquals(PairContentHasher.hash(lost), PairContentHasher.hash(found));
    }
}
