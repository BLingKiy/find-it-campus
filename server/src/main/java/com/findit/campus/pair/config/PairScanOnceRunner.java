package com.findit.campus.pair.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import com.findit.campus.pair.service.PairScanService;
import com.findit.campus.pair.web.dto.PairScanResult;

/**
 * {@code java -jar app.jar --pair-scan-once}：无 HTTP 环境下的手动扫描（架构 §5.4）。
 *
 * <p>存在的理由：演示/排障时不想为了跑一次扫描去配 {@code OPS_SCAN_TOKEN}、找 cookie、
 * 发一个 POST。一个启动参数就能把同一段逻辑跑一遍，而且输出落在启动日志里，方便取证。
 *
 * <p>语义是"跑一次<b>然后正常启动</b>"，不是"跑完就退出"：后者会让
 * {@code java -jar app.jar --pair-scan-once} 变成一个隐藏的 CLI 工具，
 * 与"这是同一个应用"的部署模型分叉。
 *
 * <p>失败不影响启动：扫描是后台能力，它失败不该让应用起不来。
 */
@Component
public class PairScanOnceRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PairScanOnceRunner.class);

    private static final String FLAG = "pair-scan-once";

    private final PairScanService scanService;

    public PairScanOnceRunner(PairScanService scanService) {
        this.scanService = scanService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!args.containsOption(FLAG)) {
            return;
        }
        try {
            PairScanResult result = scanService.scan("startup");
            log.info("pair scan once done scanned={} rescored={} degraded={} skipped={} expired={} limited={} elapsedMs={} counters={}",
                    result.scanned(), result.rescored(), result.degraded(), result.skipped(),
                    result.expired(), result.limited(), result.elapsedMs(), result.counters());
        } catch (RuntimeException e) {
            log.warn("pair scan once failed cause={}", e.getMessage());
        }
    }
}
