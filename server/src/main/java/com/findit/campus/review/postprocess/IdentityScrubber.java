package com.findit.campus.review.postprocess;

import java.util.regex.Pattern;

import com.findit.campus.common.masking.MaskingPolicy;

/**
 * 比对通道（{@code match_*}）的身份剥离器：<b>只删身份类，不动场景类</b>。
 *
 * <p>这不是"打码"的第二份实现，而是 {@link MaskingPolicy#maskIdentity(String)} 之上补三类
 * 规则层拦不住的显式身份：学号/工号、带标签的姓名、以及发布者展示名原文。手机号/证件号/
 * QQ/邮箱仍走 MaskingPolicy 同一份检测，避免两处规则分叉。
 *
 * <p><b>为什么保留地点细节与时间</b>：比对通道的职责是判断「是不是同一件物品」。
 * 把「第三教学楼 201」泛化成「教学楼」、把「昨天下午」删掉，会直接拉低区分度，
 * 最终表现为「明明是同一条却匹配不上」。公示通道才做泛化（见 display_text）。
 *
 * <p><b>能力边界（必须如实说）</b>：姓名检测依赖标签（姓名/名字/我叫/联系人）与
 * 发布者展示名精确匹配，<b>不是 NER</b>。正文里用其它方式自述真名（如「我是张三」）
 * 不保证被剥离；验收口径是规则双检 + 抽样人审，不承诺 100%。这一点写进 ADR 的风险节，
 * 不允许在答辩里说成「必然遮住」。
 */
public final class IdentityScrubber {

    /** 「姓名：张三」「我叫李四」「联系人 王五」：只吃掉标签后的 2–4 个姓名字符。 */
    private static final Pattern NAME_LABEL = Pattern.compile(
            "(姓名|名字|联系人|我叫)\\s*[:：]?\\s*[\\u4e00-\\u9fa5A-Za-z·]{2,4}");

    /** 「学号 2023123456」「工号 A1234」：标签 + 4–20 位字母数字。 */
    private static final Pattern STUDENT_NO_LABEL = Pattern.compile(
            "(学号|工号|学籍号)\\s*[:：]?\\s*[A-Za-z0-9]{4,20}");

    /** 「微信 abc_123」「QQ：12345678」：标签 + 5–20 位账号字符（纯数字 QQ 也覆盖）。 */
    private static final Pattern SOCIAL_LABEL = Pattern.compile(
            "(?i)(微信|wechat|QQ|扣扣|企鹅)\\s*[:：号]?\\s*[A-Za-z0-9_-]{5,20}");

    private static final String NAME_REPLACEMENT = "$1[已隐藏]";
    private static final String STUDENT_REPLACEMENT = "$1[已隐藏]";
    private static final String SOCIAL_REPLACEMENT = "$1[已隐藏]";

    private IdentityScrubber() {
    }

    /**
     * @param raw           原文（可为 null）
     * @param publisherName 发布者展示名；非空时把正文里出现的原文替换掉（防止自报姓名）
     * @return 身份已剥离的文本；{@code null} 原样返回
     */
    public static String scrub(String raw, String publisherName) {
        if (raw == null) {
            return null;
        }
        String s = MaskingPolicy.maskIdentity(raw);
        s = NAME_LABEL.matcher(s).replaceAll(NAME_REPLACEMENT);
        s = STUDENT_NO_LABEL.matcher(s).replaceAll(STUDENT_REPLACEMENT);
        s = SOCIAL_LABEL.matcher(s).replaceAll(SOCIAL_REPLACEMENT);
        if (publisherName != null && !publisherName.isBlank()) {
            String name = publisherName.strip();
            // 只替换显式出现的展示名；单字展示名不替换（"李"在正文里太常见，替换会毁掉语义）
            if (name.codePointCount(0, name.length()) >= 2) {
                s = s.replace(name, "[姓名已隐藏]");
            }
        }
        return s;
    }

    public static String scrub(String raw) {
        return scrub(raw, null);
    }
}
