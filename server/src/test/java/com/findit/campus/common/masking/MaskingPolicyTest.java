package com.findit.campus.common.masking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Random;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.findit.campus.record.domain.Record;
import com.findit.campus.record.domain.RecordStatus;
import com.findit.campus.record.domain.RecordSummary;
import com.findit.campus.record.domain.RecordType;
import com.findit.campus.record.web.dto.RecordDetailResponse;
import com.findit.campus.record.web.dto.RecordListItemResponse;

/**
 * 遮蔽规则的可执行版本（A1 §6.4 的正例/反例/所有者原样）。
 *
 * <p>这些断言不是"再抄一遍文档"：它们锁的是<b>用户可见输出</b>。规则改宽了（把 3 号楼 201 也打码）
 * 反例立刻红；改窄了（漏了手机号）正例立刻红；身份维度写反了（所有者也被打码）O 组立刻红。
 */
@DisplayName("MaskingPolicy：A1 §6 正例/反例/身份维度")
class MaskingPolicyTest {

    /** 验收里"响应体全局扫描 0 命中"用的同一个正则。 */
    private static final Pattern MOBILE = Pattern.compile("1[3-9]\\d{9}");

    @Test
    @DisplayName("正例 P1：地点去尾号、描述打手机号与相对时间、称呼保留首字")
    void positiveP1() {
        assertEquals("第三教学楼…", MaskingPolicy.maskLocation("第三教学楼201"));
        assertEquals("[时间已隐藏] 在三楼捡到，联系 [手机号已隐藏]",
                MaskingPolicy.maskDescription("昨天 14:30 在三楼捡到，联系 13812345678"));
        assertEquals("李**", MaskingPolicy.maskPublisherName("李同学"));
    }

    @Test
    @DisplayName("正例 P2：证件号先于手机号、QQ 只在带关键词时打码")
    void positiveP2() {
        assertEquals("东区3号宿舍楼…", MaskingPolicy.maskLocation("东区3号宿舍楼512室"));
        assertEquals("QQ[QQ号已隐藏]，证件号 [证件号已隐藏]",
                MaskingPolicy.maskDescription("QQ 123456789，证件号 110101199001011234"));
        assertEquals("王**", MaskingPolicy.maskPublisherName("王小明"));
    }

    @Test
    @DisplayName("正例 P3：长地点先截 8 码点，3 号楼 201 在句中不被误删")
    void positiveP3() {
        assertEquals("图书馆三楼自习区…", MaskingPolicy.maskLocation("图书馆三楼自习区A12座"));
        assertEquals("[时间已隐藏] 放在 3 号楼 201，电话 [手机号已隐藏]",
                MaskingPolicy.maskDescription("上周五 09:15 放在 3 号楼 201，电话 13900001111"));
        assertEquals("*", MaskingPolicy.maskPublisherName("赵"));
    }

    @Test
    @DisplayName("反例 N1/N2/N3：裸数字、房间号、绝对时间一律原样（不制造过度打码）")
    void negativeCasesStayReadable() {
        assertEquals("图书馆", MaskingPolicy.maskLocation("图书馆"));
        assertEquals("3 号楼 201 教室，无联系方式",
                MaskingPolicy.maskDescription("3 号楼 201 教室，无联系方式"));
        assertEquals("编号 12345，另一个 12345678，均非 QQ；时间 13:30 未写日期",
                MaskingPolicy.maskDescription("编号 12345，另一个 12345678，均非 QQ；时间 13:30 未写日期"));
        assertEquals("2026-10-21 13:30 在操场捡到校园卡，卡号 201",
                MaskingPolicy.maskDescription("2026-10-21 13:30 在操场捡到校园卡，卡号 201"));
        assertEquals("大学生活动中心", MaskingPolicy.maskLocation("大学生活动中心"));
    }

    @Test
    @DisplayName("全角数字/冒号归一：１３８… 与 14：30 同样命中")
    void fullWidthIsNormalized() {
        assertEquals("联系 [手机号已隐藏]", MaskingPolicy.maskDescription("联系 １３８１２３４５６７８"));
        assertEquals("[时间已隐藏] 丢的", MaskingPolicy.maskDescription("昨天 14：30 丢的"));
    }

    @Test
    @DisplayName("空值兜底：地点→校园内、称呼→匿名同学、描述 null 原样返回")
    void emptyFallbacks() {
        assertEquals(MaskingPolicy.LOCATION_FALLBACK, MaskingPolicy.maskLocation(null));
        assertEquals(MaskingPolicy.LOCATION_FALLBACK, MaskingPolicy.maskLocation("   "));
        assertEquals(MaskingPolicy.PUBLISHER_FALLBACK, MaskingPolicy.maskPublisherName(null));
        assertEquals(MaskingPolicy.PUBLISHER_FALLBACK, MaskingPolicy.maskPublisherName("  "));
        assertNull(MaskingPolicy.maskDescription(null));
    }

    @Test
    @DisplayName("打码后的描述里不存在 1[3-9]\\d{9}（与验收扫描同一正则）")
    void maskedDescriptionHasNoMobile() {
        String masked = MaskingPolicy.maskDescription(
                "昨天 14:30 联系 13812345678 / 13900001111，证件 110101199001011234，QQ 123456789");
        assertFalse(MOBILE.matcher(masked).find(), masked);
        assertFalse(masked.contains("110101199001011234"), masked);
        assertFalse(masked.contains("123456789"), masked);
    }

    @Test
    @DisplayName("身份维度：非所有者详情 contact=null 且四项打码；所有者四项原值")
    void detailIdentityDimension() {
        Record record = sampleRecord();
        RecordDetailResponse masked = RecordDetailResponse.from(record);
        assertNull(masked.getContact());
        assertEquals("第三教学楼…", masked.getLocation());
        assertEquals("李**", masked.getPublisherName());
        assertTrue(masked.getDescription().contains("[手机号已隐藏]"));
        assertFalse(masked.getIsMine());

        RecordDetailResponse owner = RecordDetailResponse.ownerView(record, 1, 0, List.of());
        assertEquals("13812345678", owner.getContact());
        assertEquals("第三教学楼201", owner.getLocation());
        assertEquals("李同学", owner.getPublisherName());
        assertEquals("昨天 14:30 在三楼捡到，联系 13812345678", owner.getDescription());
        assertTrue(owner.getIsMine());
    }

    @Test
    @DisplayName("身份维度：公共列表按 publisher_id 判 isMine，本人行原文、他人行打码")
    void listIdentityDimension() {
        RecordSummary summary = summary();
        RecordListItemResponse anonymous = RecordListItemResponse.from(summary, null);
        assertFalse(anonymous.getIsMine());
        assertEquals("第三教学楼…", anonymous.getLocation());

        RecordListItemResponse owner = RecordListItemResponse.from(summary, 1L);
        assertTrue(owner.getIsMine());
        assertEquals("第三教学楼201", owner.getLocation());

        RecordListItemResponse other = RecordListItemResponse.from(summary, 2L);
        assertFalse(other.getIsMine());
        assertEquals("第三教学楼…", other.getLocation());
    }

    @Test
    @DisplayName("英文大小写不敏感：qq 456789 也命中（(?i) 生效，保留原关键词大小写）")
    void qqKeywordIsCaseInsensitive() {
        assertEquals("qq[QQ号已隐藏]", MaskingPolicy.maskDescription("qq 456789"));
    }

    @Test
    @DisplayName("P1-1 回归 1–6：空格/换行/制表/短横/零宽/全角空格拆开的手机号仍打码")
    void separatedMobilesAreMasked() {
        assertEquals("联系 [手机号已隐藏]", MaskingPolicy.maskDescription("联系 138 1234 5678"));
        assertEquals("联系 [手机号已隐藏]", MaskingPolicy.maskDescription("联系 138\n1234\n5678"));
        assertEquals("联系 [手机号已隐藏]", MaskingPolicy.maskDescription("联系 138\t1234\t5678"));
        assertEquals("联系 [手机号已隐藏]", MaskingPolicy.maskDescription("联系 138-1234-5678"));
        assertEquals("联系 [手机号已隐藏]", MaskingPolicy.maskDescription("联系 138\u200B12345678"));
        assertEquals("联系 [手机号已隐藏]", MaskingPolicy.maskDescription("联系 138\u30001234\u30005678"));
        assertEquals("联系 [手机号已隐藏]", MaskingPolicy.maskDescription("联系 138.1234.5678"));
        assertEquals("([手机号已隐藏])", MaskingPolicy.maskDescription("(138 1234 5678)"));
        assertEquals("联系 [手机号已隐藏]",
                MaskingPolicy.maskDescription("联系 １３８　１２３４　５６７８"));
    }

    @Test
    @DisplayName("P1-1 回归 7：+86 / 86 / 0086 前缀整段替换，不留下完整号码")
    void internationalPrefixIsMaskedWithTheNumber() {
        assertEquals("[手机号已隐藏]", MaskingPolicy.maskDescription("+8613812345678"));
        assertEquals("[手机号已隐藏]", MaskingPolicy.maskDescription("+86 138 1234 5678"));
        assertEquals("[手机号已隐藏]", MaskingPolicy.maskDescription("+86-138-1234-5678"));
        assertEquals("[手机号已隐藏]", MaskingPolicy.maskDescription("8613812345678"));
        assertEquals("[手机号已隐藏]", MaskingPolicy.maskDescription("0086 138 1234 5678"));
        assertEquals("[手机号已隐藏]", MaskingPolicy.maskDescription("0086-138-1234-5678"));
        assertEquals("电话:[手机号已隐藏]。",
                MaskingPolicy.maskDescription("电话：+86 (138) 1234-5678。"));
    }

    @Test
    @DisplayName("P1-1 回归 8：带分隔符/尾位 X 的证件号同样打码")
    void separatedIdCardsAreMasked() {
        assertEquals("[证件号已隐藏]", MaskingPolicy.maskDescription("1101 0119 9001 0112 34"));
        assertEquals("[证件号已隐藏]", MaskingPolicy.maskDescription("1101-0119-9001-0112-34"));
        assertEquals("[证件号已隐藏]", MaskingPolicy.maskDescription("1101．0119．9001．0112．34"));
        assertEquals("证件 [证件号已隐藏]", MaskingPolicy.maskDescription("证件 11010119900101123X"));
    }

    @Test
    @DisplayName("归一化不扩大打码面：≤10 位数字、日期时间、房间号仍原样")
    void normalizationKeepsNegativeCasesIntact() {
        assertEquals("房间 201 电话 138 1234 567",
                MaskingPolicy.maskDescription("房间 201 电话 138 1234 567"));
        assertEquals("编号 12345678 与 123456789",
                MaskingPolicy.maskDescription("编号 12345678 与 123456789"));
        assertEquals("2026-10-21 13:30 在操场",
                MaskingPolicy.maskDescription("2026-10-21 13:30 在操场"));
        assertEquals("3 号楼 201 教室", MaskingPolicy.maskDescription("3 号楼 201 教室"));
        assertEquals("时间 1.30 集合", MaskingPolicy.maskDescription("时间 1.30 集合"));
    }

    @Test
    @DisplayName("连续数字口径不变：11 位遮蔽、12 位不截取、18 位按证件号")
    void continuousNumberSemanticsUnchanged() {
        assertEquals("[手机号已隐藏]", MaskingPolicy.maskDescription("13812345678"));
        assertEquals("138123456789", MaskingPolicy.maskDescription("138123456789"));
        assertEquals("[证件号已隐藏]", MaskingPolicy.maskDescription("110101199001011234"));
    }

    @Test
    @DisplayName("粘在其它数字后的分隔手机号：只遮号码本体，不吞前面的数字")
    void mobileGluedAfterAnotherNumberIsStillMasked() {
        assertEquals("单号12345 [手机号已隐藏]",
                MaskingPolicy.maskDescription("单号12345 138 1234 5678"));
        assertEquals("单号12345678 [手机号已隐藏]",
                MaskingPolicy.maskDescription("单号12345678 138 1234 5678"));
        assertEquals("[手机号已隐藏] 90", MaskingPolicy.maskDescription("13812345678 90"));
        assertEquals("[手机号已隐藏] 90", MaskingPolicy.maskDescription("8613812345678 90"));
    }


    @Test
    @DisplayName("出口回归：非所有者详情/列表对分隔手机号同样不会原样输出")
    void separatedNumbersAreMaskedAtResponseLayer() {
        Record record = sampleRecord();
        record.setTitle("手机 138 1234 5678");
        record.setDescription("联系 138-1234-5678");
        RecordDetailResponse detail = RecordDetailResponse.from(record);
        assertEquals("手机 [手机号已隐藏]", detail.getTitle());
        assertEquals("联系 [手机号已隐藏]", detail.getDescription());

        RecordSummary summary = summary();
        summary.setTitle("手机 138 1234 5678");
        RecordListItemResponse list = RecordListItemResponse.from(summary, null);
        assertEquals("手机 [手机号已隐藏]", list.getTitle());
    }

    @Test
    @DisplayName("随机分隔符 fuzz：任意分隔符组合都不能漏出手机号/证件号")
    void randomSeparatorFuzz() {
        String[] separators = {" ", "\t", "\n", "-", ".", "/", "\\", "_",
                "\u3000", "\u200B", "\u200C", "\u200D", "\uFEFF"};
        Random random = new Random(20261028L);
        for (int i = 0; i < 200; i++) {
            String mobile = "138" + separators[random.nextInt(separators.length)]
                    + "1234" + separators[random.nextInt(separators.length)] + "5678";
            String id = "1101" + separators[random.nextInt(separators.length)]
                    + "0119" + separators[random.nextInt(separators.length)]
                    + "9001" + separators[random.nextInt(separators.length)]
                    + "0112" + separators[random.nextInt(separators.length)] + "34";
            String maskedMobile = MaskingPolicy.maskDescription("联系" + mobile);
            assertEquals("联系[手机号已隐藏]", maskedMobile);
            String maskedId = MaskingPolicy.maskDescription("证件" + id);
            assertEquals("证件[证件号已隐藏]", maskedId);
        }
    }

    private static Record sampleRecord() {
        Record record = new Record();
        record.setId(100L);
        record.setType(RecordType.LOST);
        record.setStatus(RecordStatus.PENDING);
        record.setTitle("丢了一个背包");
        record.setDescription("昨天 14:30 在三楼捡到，联系 13812345678");
        record.setLocation("第三教学楼201");
        record.setContact("13812345678");
        record.setPublisherName("李同学");
        record.setPublisherId(1L);
        record.setOccurredAt(LocalDateTime.of(2026, 10, 20, 15, 0));
        record.setCreatedAt(LocalDateTime.of(2026, 10, 20, 15, 10));
        record.setUpdatedAt(LocalDateTime.of(2026, 10, 20, 15, 10));
        return record;
    }

    private static RecordSummary summary() {
        RecordSummary summary = new RecordSummary();
        summary.setId(100L);
        summary.setType(RecordType.LOST);
        summary.setStatus(RecordStatus.PENDING);
        summary.setTitle("丢了一个背包");
        summary.setLocation("第三教学楼201");
        summary.setPublisherId(1L);
        summary.setOccurredAt(LocalDateTime.of(2026, 10, 20, 15, 0));
        summary.setCreatedAt(LocalDateTime.of(2026, 10, 20, 15, 10));
        return summary;
    }
}
