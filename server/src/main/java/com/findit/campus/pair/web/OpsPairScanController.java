package com.findit.campus.pair.web;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.findit.campus.common.api.ApiResponse;
import com.findit.campus.common.api.ErrorCode;
import com.findit.campus.common.error.BizException;
import com.findit.campus.pair.service.PairScanService;
import com.findit.campus.pair.web.dto.PairScanResult;

/**
 * {@code POST /api/ops/pair-scan} —— 手动兜底扫描（契约 §4.18，不计入 17 个业务接口）。
 *
 * <p><b>为什么用 token 而不是登录会话</b>：这是运维入口，调用方可能是 cron 脚本/部署流水线，
 * 不该为它开一个人工账号。会话门禁（{@code AuthPaths.PUBLIC_WRITE_PATHS}）已经把它放行，
 * 这里的 token 校验是它<b>唯一</b>的认证。
 *
 * <p><b>为什么未配置时返回 404 而不是 401/503</b>：隐藏入口比"存在但拒绝"少暴露一层信息 ——
 * 扫描接口的存在性本身（"这台机器有后台配对任务"）对匿名者没有必要公开。
 * 未配置 = 该功能在此部署不存在，语义上就是 404。
 *
 * <p><b>token 比较用常数时间</b>：普通 {@code equals} 在首字节不同时立刻返回，
 * 理论上可通过响应时间逐字节猜 token。这个风险在演示环境里很低，但一行代码就能消掉，
 * 没有理由留着。
 *
 * <p>响应里<b>不含</b>任何记录正文/号码/标题，只有计数与耗时（架构 §5.4）。
 */
@RestController
@RequestMapping("/api/ops")
public class OpsPairScanController {

    private static final Logger log = LoggerFactory.getLogger(OpsPairScanController.class);

    private final PairScanService scanService;
    private final String opsToken;

    public OpsPairScanController(PairScanService scanService,
                                 @Value("${OPS_SCAN_TOKEN:}") String opsToken) {
        this.scanService = scanService;
        this.opsToken = opsToken == null ? "" : opsToken.trim();
    }

    @PostMapping("/pair-scan")
    public ApiResponse<PairScanResult> scan(@RequestHeader(value = "X-Ops-Token", required = false) String token) {
        if (opsToken.isEmpty() || !constantTimeEquals(opsToken, token)) {
            // 不区分"没传/传错/没配置"：三种情况对外都是"这个路径不存在"
            throw BizException.of(ErrorCode.RESOURCE_NOT_FOUND);
        }
        log.info("pair scan triggered by ops http");
        return ApiResponse.success(scanService.scan("http"), "扫描完成");
    }

    static boolean constantTimeEquals(String expected, String actual) {
        if (actual == null) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }
}
