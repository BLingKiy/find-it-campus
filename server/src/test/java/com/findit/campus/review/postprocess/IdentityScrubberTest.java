package com.findit.campus.review.postprocess;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.findit.campus.common.masking.MaskingPolicy;

/**
 * 比对通道的身份剥离：只删身份类，地点/时间/特征必须完整保留。
 *
 * <p>这些断言直接对应「match_text 中无姓名/手机号、地点细节完整」的验收标准。
 */
@DisplayName("IdentityScrubber：身份类剥离、场景类保留")
class IdentityScrubberTest {

    @Test
    @DisplayName("手机号（含分隔符/国际前缀）、学号、邮箱、QQ、带标签姓名都被剥离")
    void identityIsRemoved() {
        String raw = "姓名：张三，学号 2023123456，电话 138 1234 5678，邮箱 zhangsan@example.com，"
                + "QQ：12345678，昨天下午在第三教学楼 201 丢了一个黑色钱包";
        String scrubbed = IdentityScrubber.scrub(raw, null);

        assertFalse(scrubbed.contains("张三"), "带标签姓名必须剥离");
        assertFalse(scrubbed.contains("2023123456"), "学号必须剥离");
        assertFalse(scrubbed.contains("138 1234 5678"), "分隔符手机号必须剥离");
        assertFalse(scrubbed.contains("zhangsan@example.com"), "邮箱必须剥离");
        assertFalse(scrubbed.contains("12345678"), "QQ 必须剥离");
        assertTrue(scrubbed.contains("黑色钱包"), "物品特征必须保留");
        assertTrue(scrubbed.contains("第三教学楼 201"), "精准地点是比对通道的区分度，必须保留");
        assertTrue(scrubbed.contains("昨天下午"), "时间必须保留");
    }

    @Test
    @DisplayName("发布者展示名被替换；颜色/品牌/材质/时间不被泛化")
    void publisherNameAndFeatures() {
        String scrubbed = IdentityScrubber.scrub("李同学在图书馆三楼落下黑色皮质工行卡包", "李同学");

        assertFalse(scrubbed.contains("李同学"));
        assertTrue(scrubbed.contains("黑色皮质"));
        assertTrue(scrubbed.contains("工行"));
        assertTrue(scrubbed.contains("图书馆三楼"));
    }

    @Test
    @DisplayName("幂等：对剥离结果再跑一次不变化")
    void idempotent() {
        String once = IdentityScrubber.scrub("电话 13800000001，在图书馆三楼", "张三");
        assertEquals(once, IdentityScrubber.scrub(once, "张三"));
    }

    @Test
    @DisplayName("maskIdentity 不删相对时间；maskDescription 仍删（展示通道与比对通道的差异）")
    void identityOnlyKeepsRelativeTime() {
        String raw = "昨天 14:30 在图书馆";
        assertEquals(raw, MaskingPolicy.maskIdentity(raw));
        assertTrue(MaskingPolicy.maskDescription(raw).contains("时间已隐藏"));
    }

    @Test
    @DisplayName("邮箱：展示通道也必须遮蔽（v2.0 出口层漏掉的一类）")
    void emailIsMaskedAtOutput() {
        String masked = MaskingPolicy.maskDescription("联系 zhangsan@example.com");
        assertFalse(masked.contains("zhangsan@example.com"));
        assertTrue(masked.contains("邮箱已隐藏"));
    }
}
