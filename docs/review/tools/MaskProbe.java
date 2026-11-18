import com.findit.campus.common.masking.MaskingPolicy;

/**
 * 复审 harness 1：直接调用 MaskingPolicy，复现「手机号分隔符已修 / QQ 分隔与微信 ID 仍漏 /
 * 地点尾部启发式」三组结论。不启动 Spring、不连库。
 *
 * 编译与运行见 docs/review/tools/README.md。
 */
public class MaskProbe {

    private static void identity(String label, String in) {
        String out = MaskingPolicy.maskIdentity(in);
        System.out.println(label + " | in=[" + in + "] out=[" + out + "] changed=" + !out.equals(in));
    }

    private static void location(String in) {
        String out = MaskingPolicy.maskLocation(in);
        System.out.println("location | in=[" + in + "] out=[" + out + "] same=" + out.equals(in));
    }

    public static void main(String[] args) {
        // 手机号/证件号：分隔符、全角、零宽、+86 —— 已修复，全部应命中
        identity("mobile-space", "手机 138 1234 5678");
        identity("mobile-dash", "138-1234-5678");
        identity("mobile-zero", "138\u200B1234\u200B5678");
        identity("mobile-full", "１３８１２３４５６７８");
        identity("mixed", "单号12345 138 1234 5678");
        identity("email", "abc@example.com");
        identity("id18", "110101199001011234");
        identity("idX", "11010119900101123X");

        // QQ 连续命中；QQ 分隔与微信 ID 仍漏（报告 P1-2）
        identity("qq-colon", "QQ:123456789");
        identity("qq-space", "QQ 123 456");
        identity("qq-dash", "QQ 123-456");
        identity("qq-cn", "加我企鹅 123 456");
        identity("wx", "微信 abc-123456");

        // 反例：正常文本不应被过度打码
        identity("date", "2026-10-21 13:30");
        identity("amount", "金额 1,234,567 元");
        identity("room", "3 号楼 201");

        // 地点：尾部启发式（报告 C-1）
        location("图书馆三楼自习区A12座");
        location("图书馆三楼");
        location("第三教学楼三楼");
        location("北苑七号宿舍楼");
        location("三教一楼女厕所");
        location("老图书馆后门旁边的小花园长椅");
        location("13812345678");
        location("   ");
    }
}
