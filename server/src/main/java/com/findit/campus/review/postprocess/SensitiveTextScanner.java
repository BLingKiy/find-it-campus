package com.findit.campus.review.postprocess;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import com.findit.campus.common.masking.MaskingPolicy;

/**
 * 规则双检：对 LLM 产出的<b>展示文本</b>再走一遍确定性扫描，命中即 {@code REJECTED} ——
 * 模型说通过也不算数。这是发布闸门的硬约束，不是"建议"。
 *
 * <p>为什么必须双检：LLM 是概率组件，prompt 只能降低残留概率，不能证明「没有残留」。
 * 确定性规则是唯一可复现、可写进验收的部分；模型的 decision 只是第一道筛，不是放行凭证。
 *
 * <p>检测面：
 * <ul>
 *   <li>手机号/证件号/QQ/邮箱：复用 {@link MaskingPolicy#maskIdentity} 的同一份检测
 *       （含分隔符、全角、国际前缀等绕过写法），保证"展示层拦得住的，双检也拦得住"；</li>
 *   <li>学号/工号：带标签，或 10–12 位独立长数字（偏保守，宁可多拒）；</li>
 *   <li>带标签的姓名；</li>
 *   <li>精准定位：门牌/房间/座位/工位/床位，以及「图书馆 301」这类楼栋+数字组合。
 *       楼栋/楼层/区域一级<b>允许</b>（如「图书馆三楼」「第三教学楼」），不误伤。</li>
 * </ul>
 *
 * <p>能力边界：不做 NER，正文里无标签自述姓名不保证命中；验收口径是「规则双检 + 抽样人审」。
 */
public final class SensitiveTextScanner {

    private static final Pattern EMAIL = Pattern.compile(
            "(?i)(?<![A-Za-z0-9._%+-])[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}(?![A-Za-z0-9.-])");
    private static final Pattern STUDENT_LABEL = Pattern.compile(
            "(学号|工号|学籍号)\\s*[:：]?\\s*[A-Za-z0-9]{4,20}");
    private static final Pattern LONG_DIGITS = Pattern.compile("(?<!\\d)\\d{10,12}(?!\\d)");
    private static final Pattern NAME_LABEL = Pattern.compile(
            "(姓名|名字|联系人|我叫)\\s*[:：]?\\s*[\\u4e00-\\u9fa5A-Za-z·]{2,4}");
    private static final Pattern PRECISE_LOCATION = Pattern.compile(
            "(\\d{1,3}\\s*号楼\\s*\\d{1,4})"
                    + "|(\\d{1,4}\\s*(室|房|床|柜|桌|工位|座位|教室))"
                    + "|((工位|座位|床号|柜号)\\s*\\d{1,4})"
                    + "|([A-Za-z]\\d{1,3}\\s*座)"
                    + "|((图书馆|教学楼|宿舍|寝室|食堂|实验楼|办公楼|自习室)\\s*[A-Za-z]?\\d{1,4})");
    private static final Pattern QQ_LABEL = Pattern.compile(
            "(?i)(QQ|扣扣|企鹅)\\s*[:：号]?\\s*(?<!\\d)\\d{5,11}(?!\\d)");

    private SensitiveTextScanner() {
    }

    /** @return 命中的规则标签（去重、保序）；空列表 = 未命中。 */
    public static List<String> scan(String text, String publisherName) {
        Set<String> hits = new LinkedHashSet<>();
        if (text == null || text.isBlank()) {
            return List.of();
        }
        if (EMAIL.matcher(text).find()) {
            hits.add("邮箱");
        }
        if (QQ_LABEL.matcher(text).find()) {
            hits.add("QQ号");
        }
        if (STUDENT_LABEL.matcher(text).find()) {
            hits.add("学号/工号");
        } else if (LONG_DIGITS.matcher(text).find()) {
            hits.add("10-12 位长数字（疑似学号）");
        }
        // 手机号/证件号以及分隔符变体：用出口层同一份实现做差分判定
        if (!MaskingPolicy.maskIdentity(text).equals(text)) {
            hits.add("手机号/证件号/邮箱/QQ");
        }
        if (NAME_LABEL.matcher(text).find()) {
            hits.add("姓名");
        }
        if (publisherName != null && publisherName.strip().length() >= 2
                && text.contains(publisherName.strip())) {
            hits.add("发布者姓名");
        }
        if (PRECISE_LOCATION.matcher(text).find()) {
            hits.add("精准门牌/房间/座位");
        }
        return List.copyOf(hits);
    }

    public static List<String> scan(String text) {
        return scan(text, null);
    }

    /** 把多个字段的命中合并成一条可落库的理由（≤500 字）。 */
    public static String describeHits(List<String> hits) {
        if (hits == null || hits.isEmpty()) {
            return "";
        }
        List<String> unique = new ArrayList<>(new LinkedHashSet<>(hits));
        return String.join("、", unique);
    }
}
