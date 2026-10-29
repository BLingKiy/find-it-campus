package com.findit.campus.common.masking;

import java.text.Normalizer;
import java.util.Arrays;
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

    /** 邮箱：v2.0 出口层漏掉的一类身份信息，v2.1 与手机号同口径处理。 */
    private static final Pattern EMAIL_RE = Pattern.compile(
            "(?i)(?<![A-Za-z0-9._%+-])[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}(?![A-Za-z0-9.-])");

    /** 「昨天 14:30」类相对时间；绝对时间（2026-10-21 13:30）刻意不命中（N3 反例）。 */
    private static final Pattern RELATIVE_TIME_RE = Pattern.compile(
            "(?:昨天|前天|今天|昨日|当日|上周[一二三四五六日天]?|本周[一二三四五六日天]?|周[一二三四五六日天])"
                    + "\\s*(?:上午|下午|晚上|中午|凌晨|早上)?\\s*\\d{1,2}\\s*[:：]\\s*\\d{2}");

    private static final String ID_REPLACEMENT = "[证件号已隐藏]";
    private static final String MOBILE_REPLACEMENT = "[手机号已隐藏]";
    private static final String QQ_REPLACEMENT = "$1[QQ号已隐藏]";
    private static final String EMAIL_REPLACEMENT = "[邮箱已隐藏]";
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
     * 描述/标题打码（A1 §6.2）：先做“分隔符归一”检测，再按固定顺序跑四条正则。
     *
     * <p>标题额外套用同一函数是<b>防御性追加</b>（§6.2 末条）：标题也是自由文本，
     * 用户完全可能把手机号写进标题；不属于四条硬判据，但不做就是漏口。
     *
     * <p>为什么多一遍“分隔符归一”：规则 1/2 的原始正则要求数字连续，但用户写号码是
     * {@code 138 1234 5678}、{@code 138-1234-5678}、{@code 138,1234,5678}、{@code +86 138…}
     * 这种自然形态。只按原文匹配等于把“写法”当成“是否打码”的开关 —— 加一个空格或标点就能绕过
     * 隐私分档（F3 ZHYYKFX-60 P1-1 / F3-R ZHYYKFX-75）。这里把空格（含全角空格/零宽字符）、
     * 制表、换行、{@code - . / \ , : ;}、中文标点 {@code ，：；、} 与各类括号视为数字间分隔符，
     * 归一后若整段恰好是 11 位手机号（含 {@code +86 / 86 / 0086} 前缀）或 18 位证件号，
     * 则替换<b>原文中对应的整段</b>（含前缀与分隔符）。
     *
     * <p>边界刻意收窄，避免过度打码：
     * <ul>
     *   <li>只处理“整段归一后恰好命中”的数字串；整段不是单个号码时，再按<b>原文左右边界</b>
     *       找独立成号的 11 位手机号（左右都不是数字）—— 与 {@link #MOBILE_RE} 的边界语义一致，
     *       避免“单号12345 138 1234 5678”这类把号码粘在其它数字后面绕过。</li>
     *   <li>裸 5–9 位数字、日期、时间、房间号不命中，N1/N2/N3 反例保持原样。</li>
     *   <li>归一只用于“检测与替换区间”，不改写整条文案的标点（不做整体 NFKC）。</li>
     * </ul>
     *
     * @param raw 原始文本；{@code null} 原样返回（可选字段）
     */
    public static String maskDescription(String raw) {
        String s = maskIdentity(raw);
        if (s == null) {
            return null;
        }
        return RELATIVE_TIME_RE.matcher(s).replaceAll(Matcher.quoteReplacement(TIME_REPLACEMENT));
    }

    /**
     * 只遮蔽<b>身份类</b>信息：证件号、手机号（含分隔符/国际前缀）、QQ、邮箱。
     *
     * <p>与 {@link #maskDescription} 的差别：<b>不动相对时间</b>。发布闸门的比对通道
     * （{@code match_*}）要求「时间、地点细节、物品特征完整保留」，否则配对区分度被削；
     * 但姓名/手机号/学号/邮箱/QQ 这些能指认到人的信息必须一条不剩。
     *
     * <p>展示通道仍走 {@link #maskDescription}（多一条相对时间规则），两个通道共用同一份
     * 号码检测实现，不会出现「展示拦住了、比对没拦住」。
     *
     * @param raw 原文；{@code null} 原样返回
     */
    public static String maskIdentity(String raw) {
        if (raw == null) {
            return null;
        }
        String s = normalizeDigitsAndColon(raw);
        s = maskSeparatedContactNumbers(s);
        s = ID_RE.matcher(s).replaceAll(Matcher.quoteReplacement(ID_REPLACEMENT));
        s = MOBILE_RE.matcher(s).replaceAll(Matcher.quoteReplacement(MOBILE_REPLACEMENT));
        s = QQ_RE.matcher(s).replaceAll(QQ_REPLACEMENT);
        s = EMAIL_RE.matcher(s).replaceAll(Matcher.quoteReplacement(EMAIL_REPLACEMENT));
        return s;
    }

    /**
     * 分隔符归一后的联系方式检测 —— 规则 1/2 的<b>输入规范化</b>，不是第五条规则。
     *
     * <p>扫描“数字 + 分隔符”组成的最长片段：片段内的数字按原顺序归一成连续串，
     * 只有归一结果<b>整段</b>命中 18 位证件号 / 11 位手机号（含国际前缀）时才替换，
     * 未命中的片段按原文保留。这样“打不打码”由号码本身决定，而不是由用户怎么敲空格决定。
     */
    private static String maskSeparatedContactNumbers(String s) {
        int length = s.length();
        StringBuilder out = new StringBuilder(length);
        int i = 0;
        while (i < length) {
            char first = s.charAt(i);
            boolean plus = isPlusSign(first);
            if (!isAsciiDigit(first) && !(plus && hasDigitAfterSeparators(s, i + 1))) {
                out.append(first);
                i++;
                continue;
            }

            int[] digitPositions = new int[16];
            int digitCount = 0;
            int cursor = plus ? i + 1 : i;
            int runEnd = -1;
            while (cursor < length) {
                char c = s.charAt(cursor);
                if (isAsciiDigit(c)) {
                    if (digitCount == digitPositions.length) {
                        digitPositions = Arrays.copyOf(digitPositions, digitCount * 2);
                    }
                    digitPositions[digitCount++] = cursor;
                    runEnd = cursor + 1;
                    cursor++;
                } else if (isDigitSeparator(c)) {
                    cursor++;
                } else {
                    break;
                }
            }
            if (runEnd < 0) {
                // 形如“+ 号”但后面没有数字：按普通字符输出。
                out.append(first);
                i++;
                continue;
            }

            String replacement = null;
            int replaceEnd = runEnd;
            if (digitCount == 18) {
                // 证件号：18 位，允许中间被分隔符拆开（1101 0119 9001 0112 34）。
                replacement = ID_REPLACEMENT;
            } else if (digitCount == 17 && runEnd < length && isIdTail(s.charAt(runEnd))) {
                // 证件号尾位 X，且 X 与最后一位数字相邻。
                replacement = ID_REPLACEMENT;
                replaceEnd = runEnd + 1;
            } else if (digitCount == 11 && isMobileAt(s, digitPositions, 0)) {
                replacement = MOBILE_REPLACEMENT;
            } else if (digitCount == 13
                    && digitsMatch(s, digitPositions, 0, "86")
                    && isMobileAt(s, digitPositions, 2)) {
                // +86 / 86 前缀：整段（含国家码）一起替换，否则号码本体仍可从响应里读出。
                replacement = MOBILE_REPLACEMENT;
            } else if (digitCount == 15
                    && digitsMatch(s, digitPositions, 0, "0086")
                    && isMobileAt(s, digitPositions, 4)) {
                replacement = MOBILE_REPLACEMENT;
            }

            if (replacement == null) {
                out.append(maskMobilesInsideRun(s, i, runEnd, digitPositions, digitCount));
            } else {
                out.append(replacement);
            }
            i = replaceEnd;
        }
        return out.toString();
    }

    /**
     * 片段整体不是单个号码时（例如“单号12345 138 1234 5678”被空格连成一段），
     * 按原文左右边界继续找“独立成号”的手机号：11 位数字左右都不是数字，才算一个完整号码。
     *
     * <p>为什么不一上来就做子串匹配：那会把 {@code 2026-10-21 13:30} 这类正常文本打碎。
     * 先整段判断（绝大多数场景只有号码本身），失败后才用与 {@link #MOBILE_RE} 相同的
     * “左右非数字”口径补漏，能挡住“号码前面粘一个数字”的绕过，又不扩大短号打码面。
     */
    private static String maskMobilesInsideRun(String s, int runStart, int runEnd,
                                               int[] digitPositions, int digitCount) {
        int length = s.length();
        StringBuilder chunk = null;
        int emitted = runStart;
        int p = 0;
        while (p + 11 <= digitCount) {
            int mobileStart = digitPositions[p];
            int mobileEnd = digitPositions[p + 10] + 1;
            int prefixStart = -1;
            if (p == 4 && digitsMatch(s, digitPositions, 0, "0086")) {
                prefixStart = runStart;
            } else if (p == 2 && digitsMatch(s, digitPositions, 0, "86")) {
                prefixStart = runStart;
            }
            int effectiveStart = prefixStart >= 0 ? prefixStart : mobileStart;
            boolean leftBoundary = effectiveStart == 0 || !isAsciiDigit(s.charAt(effectiveStart - 1));
            boolean rightBoundary = mobileEnd >= length || !isAsciiDigit(s.charAt(mobileEnd));
            if (leftBoundary && rightBoundary && isMobileAt(s, digitPositions, p)) {
                if (chunk == null) {
                    chunk = new StringBuilder(runEnd - runStart);
                }
                chunk.append(s, emitted, effectiveStart).append(MOBILE_REPLACEMENT);
                emitted = mobileEnd;
                p += 11;
            } else {
                p++;
            }
        }
        if (chunk == null) {
            return s.substring(runStart, runEnd);
        }
        chunk.append(s, emitted, runEnd);
        return chunk.toString();
    }

    private static boolean isAsciiDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isPlusSign(char c) {
        return c == '+' || c == '＋';
    }

    private static boolean isIdTail(char c) {
        return c == 'X' || c == 'x';
    }

    /**
     * 数字之间可忽略的分隔符：空白（含全角空格、NBSP、{@code \t\n\r}）、零宽字符、常见连写符号与括号。
     *
     * <p>包含 {@code , : ;} 与全角 {@code ， ： ；}（另含顿号 {@code 、}，同一根因的剩余变体）：
     * 号码的自然写法不只有空格和短横，{@code 138,1234,5678}、{@code 138:1234:5678}、
     * {@code 138;1234:5678}、{@code 138、1234、5678} 都是「号码 + 分隔符 + 号码」的整段。
     * 把分隔符当开关，等于用户加一个标点就能绕过隐私分档（F3-R 实测残留，ZHYYKFX-75）。
     *
     * <p>为什么敢收逗号（F3-P1 曾刻意排除）：收窄风险不靠「不认逗号」，而靠<b>整段长度精确匹配</b>。
     * 分隔符只决定「哪些数字属于同一段」，是否替换仍要求归一后<b>整段</b>恰好 11 位手机号 /
     * 18 位证件号（含国际前缀）。{@code 1,234,567} 归一后只有 7 位、{@code 1,234,567,890} 只有 10 位，
     * 都不满足；超过 18 位的段整体不命中，子串补漏还要求左右不是数字，因此
     * {@code 2026-10-21 13:30}、{@code 第 1,2,3 项}、{@code 金额 1,234,567 元} 保持原样
     * （见 {@code MaskingPolicyTest} 的标点组与反例组）。
     */
    private static boolean isDigitSeparator(char c) {
        if (c == ' ' || Character.isWhitespace(c) || Character.isSpaceChar(c)) {
            return true;
        }
        switch (c) {
            case '-':
            case '.':
            case '/':
            case '\\':
            case '(':
            case ')':
            case '[':
            case ']':
            case '_':
            case ',': // 半角逗号
            case ':': // 半角冒号（全角冒号先由 normalizeDigitsAndColon 归一，这里再兜一层）
            case ';': // 半角分号
            case '，': // 全角逗号
            case '：': // 全角冒号
            case '；': // 全角分号
            case '、': // 顿号（与逗号同根因的中文枚举分隔符）
            case '\u200B': // ZERO WIDTH SPACE
            case '\u200C': // ZERO WIDTH NON-JOINER
            case '\u200D': // ZERO WIDTH JOINER
            case '\uFEFF': // ZERO WIDTH NO-BREAK SPACE / BOM
            case '\u2060': // WORD JOINER
            case '－': // 全角连字符
            case '．': // 全角句点
            case '／': // 全角斜杠
            case '＼': // 全角反斜杠
            case '（':
            case '）':
            case '［':
            case '］':
                return true;
            default:
                return false;
        }
    }

    private static boolean hasDigitAfterSeparators(String s, int from) {
        int i = from;
        while (i < s.length() && isDigitSeparator(s.charAt(i))) {
            i++;
        }
        return i < s.length() && isAsciiDigit(s.charAt(i));
    }

    /** 只有“1[3-9]”开头这一步需要看内容；其余 9 位由扫描循环保证一定是 ASCII 数字。 */
    private static boolean isMobileAt(String s, int[] digitPositions, int start) {
        char first = s.charAt(digitPositions[start]);
        char second = s.charAt(digitPositions[start + 1]);
        return first == '1' && second >= '3' && second <= '9';
    }

    private static boolean digitsMatch(String s, int[] digitPositions, int start, String expected) {
        for (int k = 0; k < expected.length(); k++) {
            if (s.charAt(digitPositions[start + k]) != expected.charAt(k)) {
                return false;
            }
        }
        return true;
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
