package com.findit.campus.pair.engine;

import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.findit.campus.pair.domain.PairSide;
import com.findit.campus.review.postprocess.IdentityScrubber;

/**
 * Prompt 的唯一构造点（架构 §8.2，{@code prompt_version = pair-score-v1}）。
 *
 * <p><b>身份剥离在这里做，而且只在这里做</b>：{@code title/description/location} 是比对通道
 * （{@code match_*}），再过一遍 {@link IdentityScrubber} 后才拼进 prompt —— 只删身份类，
 * <b>不泛化地点、不删时间</b>，否则「第三教学楼 201」被抹成「教学楼」会直接拉低区分度。
 * {@code contact} 根本不在 {@link PairSide} 里
 * （类型层面拿不到），因此"号码进 prompt"这件事无法通过改调用方发生。
 *
 * <p>为什么把 system/user 拆成两个常量 + 一个方法：单测可以直接断言
 * 「prompt 里不出现手机号」「同一对记录两次构造完全一致」，不需要起 HTTP。
 */
public final class PairPrompt {

    /** 落 {@code t_pair_score.prompt_version}；改 prompt 文本必须同时改这个值。 */
    /**
     * v2：输入改为比对通道（只剥离身份类，不再泛化地点/时间）。改 prompt 语义必须改版本号，
     * 否则库里新旧两套分数无法区分。{@code findit.review.mode=off} 时输入回退为展示文本
     * （与 v2.0 一致），但版本号仍是 v2 —— 口径差异由 mode 决定，不再用版本号表达。
     */
    public static final String VERSION = "pair-score-v2";

    public static final String SYSTEM = """
            你是校园失物招领的配对评估器。只输出 JSON，不要输出 Markdown 或解释：
            {"score": <0 到 1 的数字，保留两位小数>, "reason": "<40 字以内中文依据>"}""";

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private PairPrompt() {
    }

    /** 供 LLM 客户端构造 messages；顺序固定 system → user。 */
    public static List<Map<String, String>> messages(PairScoreCommand command) {
        Map<String, String> system = new LinkedHashMap<>();
        system.put("role", "system");
        system.put("content", SYSTEM);
        Map<String, String> user = new LinkedHashMap<>();
        user.put("role", "user");
        user.put("content", userPrompt(command));
        return List.of(system, user);
    }

    /**
     * 用户消息：两端各一行，字段顺序固定。
     *
     * <p>空值统一写成 {@code 未填}：模型看到 {@code null} 容易在 reason 里输出英文 "null"，
     * 而这个 reason 会进前端卡片。
     */
    public static String userPrompt(PairScoreCommand command) {
        return "【寻物】" + describe(command.lost()) + "\n"
                + "【招领】" + describe(command.found()) + "\n"
                + "判断两者是否可能为同一件物品；score 越高越可能。";
    }

    static String describe(PairSide side) {
        return "标题：" + orUnfilled(IdentityScrubber.scrub(side.title()))
                + "；描述：" + orUnfilled(IdentityScrubber.scrub(side.description()))
                + "；地点：" + orUnfilled(IdentityScrubber.scrub(side.location()))
                + "；分类：" + orUnfilled(side.categoryName())
                + "；时间：" + (side.occurredAt() == null ? "未填" : side.occurredAt().format(TIME_FORMAT));
    }

    private static String orUnfilled(String value) {
        return value == null || value.isBlank() ? "未填" : value;
    }
}
