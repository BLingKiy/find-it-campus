package com.findit.campus.common.masking;

import java.text.Normalizer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 输出层遮蔽的<b>唯一</b>实现（契约 A1 §6：列表 / 详情 / {@code /api/match} / 我的启事 / 通知五个出口共用）。
 *
 * <p>为什么必须单点：遮蔽不是"某个接口的展示细节"，而是隐私边界。一旦列表与详情各写一份，
 * 就会出现"列表里打码了、详情里忘了"的漏口，而且这种漏口在功能测试里完全看不出来 ——
 * 只有拿着手机号去 grep 响应体才能发现。这里把规则集中成纯函数，出口只负责"传不传原文"。
 *
 * <p>三条纪律：
 * <ol>
 *   <li><b>只在输出层做</b>：数据库里 {@code contact} 仍是明文（C1 口径）。这样本人回显不需要反解，
 *       也不引入密钥管理；代价是能读 DB 的人能看到号码，靠部署侧访问控制兜底（V2-ADR-003）。</li>
 *   <li><b>不扩大规则</b>：裸 5–9 位数字（学号、卡号、房间号）不打码，{@code 3 号楼 201} 必须原样保留。
 *       为了"更安全"把规则放宽，会把正常描述打成筛子，用户看不懂自己在读什么。</li>
 *   <li><b>返回新字符串，不改入参</b>：DTO 装配失败时不能把半个打码结果残留到实体上。</li>
 * </ol>
 *
 * <p>规则逐字对应 {@code docs/design/v2-architecture.md} §6.1–§6.3（A1 冻结稿）。
 */
public final class MaskingPolicy {

    /** 地点为空/只剩定位信息时的兜底文案（不是空串 —— 前端不能拿空地点渲染）。 */
    public static final String LOCATION_FALLBACK = "校园内";

    /** 联系人称呼为空时的兜底文案。 */
    public static final String PUBLISHER_FALLBACK = "匿名同学";

    /** 地点截断长度：8 个 Unicode 码点（不是 UTF-16 char，emoji 不会被劈成两半）。 */
    private static final int LOCATION_MAX_CODE_POINTS = 8;

    /**
     * 地点末尾的精确定位片段（楼栋/楼层/房间/座位）。
     *
     * <p>{@code \d{1,4}} 覆盖 201/3021；{@code [A-Za-z]\d{1,4}} 覆盖 A12 座；中文数字覆盖「三楼」。
     * 注意这里<b>没有</b>单独的房间号规则：{@code 3 号楼 201} 里的 {@code 201} 只有贴着末尾时才被去掉，
     * 出现在句子中间（描述里）不会命中 —— 这正是 P3 正例与 N1 反例的分界。
     */
    private static final Pattern LOCATION_TAIL = Pattern.compile(
            "(?:\\d{1,4}|[A-Za-z]\\d{1,4}|[一二三四五六七八九十百零两]{1,3})"
                    + "\\s*(?:号楼|号|栋|楼|层|室|房|床|柜|桌|座|排|教室|自习室|宿舍|寝室|办公室)?\\s*$");

    private static final Pattern TRAILING_SEPARATORS = Pattern.compile("[\\s,，、·/\\\\-]+$");

    /** 18 位证件号；必须先于手机号规则执行，否则长数字串会被手机号规则切成两段。 */
    private static final Pattern ID_RE = Pattern.compile("(?<!\\d)\\d{17}[\\dXx](?!\\d)");

    /** 11 位手机号；前后断言保证不会从更长的数字串里截取。 */
    private static final Pattern MOBILE_RE = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");

    /** 带 QQ 关键词的 5–9 位号；裸数字不命中（学号/卡号/房间号不掩码）。 */
    private static final Pattern QQ_RE = Pattern.compile("(?i)(QQ|扣扣|企鹅)\\s*[:：号]?\\s*(?<!\\d)\\d{5,9}(?!\\d)");

    /** 「昨天 14:30」类相对时间；绝对时间（2026-10-21 13:30）刻意不命中（N3 反例）。 */
    private static final Pattern RELATIVE_TIME_RE = Pattern.compile(
            "(?:昨天|前天|今天|昨日|当日|上周[一二三四五六日天]?|本周[一二三四五六日天]?|周[一二三四五六日天])"
                    + "\\s*(?:上午|下午|晚上|中午|凌晨|早上)?\\s*\\d{1,2}\\s*[:：]\\s*\\d{2}");

    private static final String ID_REPLACEMENT = "[证件号已隐藏]";
    private static final String MOBILE_REPLACEMENT = "[手机号已隐藏]";
    private static final String QQ_REPLACEMENT = "$1[QQ号已隐藏]";
    private static final String TIME_REPLACEMENT = "[时间已隐藏]";

    private MaskingPolicy() {
    }

    /**
     * 地点截断（A1 §6.1）：先按码点截 8 个，再去掉末尾的精确定位片段。
     *
     * <p>顺序不可换：先截断才能挡住长尾描述；先删定位片段会把「图书馆三楼自习区A12座」里的
     * {@code 座} 留在末尾，截断后变成「图书馆三楼自习区A1…」这种既泄露又难读的结果。
     *
     * @param raw 原始地点；{@code null}/空白返回「校园内」
     */
    public static String maskLocation(String raw) {
        String s = raw == null ? "" : Normalizer.normalize(raw, Normalizer.Form.NFKC);
        s = s.strip().replaceAll("\\s+", " ");
        if (s.isEmpty()) {
            return LOCATION_FALLBACK;
        }
        boolean truncated = false;
        int[] codePoints = s.codePoints().toArray();
        if (codePoints.length > LOCATION_MAX_CODE_POINTS) {
            s = new String(codePoints, 0, LOCATION_MAX_CODE_POINTS);
            truncated = true;
        }
        Matcher tail = LOCATION_TAIL.matcher(s);
        if (tail.find()) {
            String before = s.substring(0, tail.start());
            if (before.isEmpty()) {
                return LOCATION_FALLBACK;
            }
            s = before;
            truncated = true;
        }
        s = TRAILING_SEPARATORS.matcher(s).replaceAll("");
        if (s.isEmpty()) {
            return LOCATION_FALLBACK;
        }
        return truncated ? s + "…" : s;
    }

    /**
     * 描述/标题打码（A1 §6.2）：按固定顺序跑四条正则。
     *
     * <p>标题额外套用同一函数是<b>防御性追加</b>（§6.2 末条）：标题也是自由文本，
     * 用户完全可能把手机号写进标题；不属于四条硬判据，但不做就是漏口。
     *
     * @param raw 原始文本；{@code null} 原样返回（可选字段）
     */
    public static String maskDescription(String raw) {
        if (raw == null) {
            return null;
        }
        String s = normalizeDigitsAndColon(raw);
        s = ID_RE.matcher(s).replaceAll(Matcher.quoteReplacement(ID_REPLACEMENT));
        s = MOBILE_RE.matcher(s).replaceAll(Matcher.quoteReplacement(MOBILE_REPLACEMENT));
        s = QQ_RE.matcher(s).replaceAll(QQ_REPLACEMENT);
        s = RELATIVE_TIME_RE.matcher(s).replaceAll(Matcher.quoteReplacement(TIME_REPLACEMENT));
        return s;
    }

    /**
     * 联系人称呼打码（A1 §6.3）：空 → 匿名同学；1 个码点 → {@code *}；否则首码点 + {@code **}。
     *
     * <p>只保留首字是为了让读者还能分辨"是不是同一个人发的"，同时不暴露完整姓名。
     */
    public static String maskPublisherName(String raw) {
        if (raw == null || raw.isBlank()) {
            return PUBLISHER_FALLBACK;
        }
        String s = raw.strip();
        int first = s.codePointAt(0);
        int count = s.codePointCount(0, s.length());
        if (count == 1) {
            return "*";
        }
        return new String(Character.toChars(first)) + "**";
    }

    /**
     * 只做两种归一化：全角数字 → ASCII、全角冒号 → ASCII。
     *
     * <p>不整体 NFKC：那会把中文标点改写成半角（{@code ，} → {@code ,}），用户看到的描述会变形；
     * 而号码/时间规则需要处理的只有数字与冒号。
     */
    private static String normalizeDigitsAndColon(String raw) {
        StringBuilder normalized = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c >= '０' && c <= '９') {
                normalized.append((char) (c - 0xFEE0));
            } else if (c == '：') {
                normalized.append(':');
            } else {
                normalized.append(c);
            }
        }
        return normalized.toString();
    }
}
