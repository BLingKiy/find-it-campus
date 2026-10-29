package com.findit.campus.review.postprocess;

import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 发布后处理 + AI 审核的<b>唯一</b> prompt 构造点（{@code prompt_version = pair-postprocess-v1}）。
 *
 * <p>一次调用三份产出：展示文本（title/description/location）、{@code summary}、
 * 审核判定（{@code decision/reasons}）。省 token 只是附带收益；真正的理由是
 * <b>审核必须看到与展示完全相同的那份文本</b> —— 分成两次调用会出现「审核的是 A、
 * 上架的是 B」的口径分叉。
 *
 * <p>送入 LLM 的输入是已经过 {@link IdentityScrubber} 的比对文本：
 * 姓名/学号/手机号/QQ/微信/邮箱一律不送；物品特征、楼层区域、时间可以送。
 * 这是已知的数据外发面，写在 ADR 的「风险与边界」里，答辩口径是
 * 「用户身份信息没有出服务器」，而不是「什么数据都没出服务器」。
 *
 * <p>目标不是「把文本变模糊」，而是「不能指认到人、但仍能认得出物」。
 * 必须删/必须留两组要求各写成清单并各给正反例，避免模型把区分性特征一并删掉。
 */
public final class PostprocessPrompt {

    public static final String VERSION = "pair-postprocess-v1";

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    public static final String SYSTEM = """
            你是校园失物招领平台的“发布后处理器 + 审核员”。你要同时完成三件事：
            1) 把输入文本改写成可公示的展示文本；
            2) 生成一句卡片摘要；
            3) 判断这条内容是否允许对外展示。

            只输出一个 JSON 对象，不要 Markdown、不要解释、不要代码块。字段：
            {
              "title": "改写后的标题，2-64 字",
              "description": "改写后的描述，2-2000 字",
              "location": "泛化后的地点，1-128 字",
              "summary": "卡片摘要，不超过 40 个汉字",
              "decision": "APPROVE 或 REJECT",
              "reasons": ["中文理由，最多 3 条"]
            }

            【必须删除（出现即不得出现在展示文本中）】
            - 姓名、昵称、称呼、学号、工号、班级姓名组合；
            - 手机号、QQ、微信、邮箱、任何联系方式（含用空格/短横/逗号拆开的写法）；
            - 精准门牌/房间号/工位号/座位号/床号/柜号（如“3 号楼 201”“A12 座”“工位 35”）；
            - 能还原到具体个人的时间+地点组合（如“昨天 14:30 我在 3 号楼 201 收拾东西”）。
            正例：输入“张三在 3 号楼 201 丢了手机，电话 13800000001”
                  → title“丢失一部手机”，description 只保留物品特征，location“3 号楼附近”。
            正例：输入“李四，学号 2023123456，在图书馆三楼 A12 座落下一个黑色卡包”
                  → 展示文本不出现姓名/学号/A12 座，location 只到“图书馆三楼”。
            反例：把“3 号楼 201”原样保留，或把手机号写成“138****0001”仍留在正文里。
            反例：把“张三”改成“张同学”仍能指认到人。

            【必须保留（删掉会让人认不出物）】
            - 颜色、品牌、材质、独特标记/贴纸/挂件/破损等区分性特征；
            - 物品类别与型号；
            - 定位只泛化到“楼栋 / 楼层 / 区域”一级（如“第三教学楼”“图书馆三楼”“南操场”），
              不要泛化成“校园内”这种没有区分度的词。
            正例：输入“黑色皮质钱包，工行卡，靠窗座位”
                  → 保留“黑色/皮质/工行卡/靠窗”，删掉具体座位号。
            正例：输入“白色 iPhone 14，透明壳，背面小熊贴纸”
                  → 全部保留，这些是匹配的关键线索。
            反例：把“黑色卡包”改成“深色卡包”（改变事实、拉低匹配）。
            反例：把“透明壳+小熊贴纸”删成“一部手机”（过度改写、匹配不到）。

            【summary 要求】
            一句话，≤40 个汉字，必须包含三要素：物品类别 + 关键特征 + 模糊地点。
            例：“黑色皮质钱包，工行卡，图书馆三楼”。

            【decision 规则】
            - 改写后仍含任何“必须删除”的信息 → REJECT，并在 reasons 指出具体类别；
            - 无法在保留关键特征的前提下完成去身份化 → REJECT；
            - 其余 → APPROVE，reasons 用 1-3 条中文说明依据（例如“已去除姓名与门牌，
              保留黑色皮质与工行卡特征”）。
            - 不要因为“内容看起来正常”就跳过检查；也不要因为“地点不够精确”而 REJECT。
            """;

    private PostprocessPrompt() {
    }

    public static List<Map<String, String>> messages(PostprocessCommand command) {
        Map<String, String> system = new LinkedHashMap<>();
        system.put("role", "system");
        system.put("content", SYSTEM);
        Map<String, String> user = new LinkedHashMap<>();
        user.put("role", "user");
        user.put("content", userPrompt(command));
        return List.of(system, user);
    }

    public static String userPrompt(PostprocessCommand command) {
        return "【类型】" + (command.type() == null ? "未填" : command.type())
                + "\n【分类】" + orUnfilled(command.categoryName())
                + "\n【标题】" + orUnfilled(command.title())
                + "\n【描述】" + orUnfilled(command.description())
                + "\n【地点】" + orUnfilled(command.location())
                + "\n【时间】" + (command.occurredAt() == null ? "未填" : command.occurredAt().format(TIME_FORMAT))
                + "\n请按 system 的 JSON 契约输出。";
    }

    private static String orUnfilled(String value) {
        return value == null || value.isBlank() ? "未填" : value;
    }
}
