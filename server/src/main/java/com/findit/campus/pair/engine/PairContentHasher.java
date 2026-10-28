package com.findit.campus.pair.engine;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import com.findit.campus.pair.domain.PairSide;

/**
 * pair 幂等键的内容哈希（架构 §8.5 的可执行实现）。
 *
 * <pre>
 * hash = lower(hex(sha256(
 *     trim(title) + "\n" + trim(description) + "\n" + trim(location) + "\n" +
 *     (categoryId == null ? "" : categoryId) + "\n" + type)))
 * </pre>
 *
 * <p><b>覆盖哪些字段是口径，不是实现细节</b>：title/description/location/category 是 LLM 打分的
 * 全部依据；它们没变，重算一定得到同一个分数（temperature=0 且同一模型版本），
 * 所以跳过是安全的。{@code contact}/{@code publisherName} 不进 prompt，也就不进 hash ——
 * 改一个联系人称呼不该触发一次 LLM 计费。
 *
 * <p>{@code occurredAt} 也不进 hash：它是业务时间，改了不影响"是不是同一件物品"的判断，
 * 但会改 prompt 文本。这里选择不进（少一次计费），代价是 prompt 里的时间可能与库里的旧值不同步，
 * 而时间对分数的影响远小于标题/描述 —— 这是**有意的取舍**，不是遗漏。
 *
 * <p>为什么不用 {@code String.hashCode()}：32 位、碰撞概率随 pair 数平方增长；
 * 十万级 pair 下碰撞会表现为"改了内容却不重算"，属于最难排查的一类静默错误。
 */
public final class PairContentHasher {

    private PairContentHasher() {
    }

    /** 一端的哈希；{@code null} 字段按空串处理（DDL 里 title/description/location 都是 NOT NULL）。 */
    public static String hash(PairSide side) {
        String canonical = trim(side.title()) + "\n"
                + trim(side.description()) + "\n"
                + trim(side.location()) + "\n"
                + (side.categoryId() == null ? "" : side.categoryId().toString()) + "\n"
                + (side.type() == null ? "" : side.type().name());
        return sha256Hex(canonical);
    }

    static String sha256Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                builder.append(Character.forDigit((b >> 4) & 0xF, 16));
                builder.append(Character.forDigit(b & 0xF, 16));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            // JDK 必须提供 SHA-256；走到这里说明运行环境被人动过，不能降级成弱哈希
            throw new IllegalStateException("JDK 缺少 SHA-256 实现", e);
        }
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
