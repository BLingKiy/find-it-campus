import com.findit.campus.review.postprocess.SensitiveTextScanner;

import java.util.List;

/**
 * 复审 harness 2：直接调用发布闸门的规则双检，复现「归一化变更被当成命中（报告 P1-1）」
 * 与「QQ 分隔/微信 ID 不命中（报告 P1-2）」。不启动 Spring、不连库。
 */
public class GateProbe {

    private static void scan(String in) {
        List<String> hits = SensitiveTextScanner.scan(in, null);
        System.out.println("in=[" + in + "] hits=" + hits);
    }

    public static void main(String[] args) {
        // 误拒：只有全角冒号/全角数字，也被记成「手机号/证件号/邮箱/QQ」
        scan("时间：昨天 14:30");
        scan("摘要：黑色钱包");
        scan("物品：黑色皮质钱包，地点：图书馆三楼");
        scan("２０２６年１０月２１日");
        scan("第三教学楼：三楼");

        // 对照：半角冒号不误判
        scan("时间:昨天 14:30");

        // 漏检：QQ 分隔 / 微信 ID
        scan("QQ 123 456");
        scan("QQ 123-456");
        scan("微信 abc_123456");

        // 正常命中
        scan("QQ:123456789");
        scan("手机 138 1234 5678");
        scan("学号 2023123456");

        // 地点规则：楼层允许、门牌不允许
        scan("第三教学楼三楼");
        scan("第三教学楼 201");
    }
}
