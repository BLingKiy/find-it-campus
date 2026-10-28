package com.findit.campus.pair.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.findit.campus.pair.domain.AccessLog;
import com.findit.campus.pair.domain.AccessResult;
import com.findit.campus.pair.domain.PairAccess;
import com.findit.campus.pair.mapper.AccessLogMapper;

/**
 * 写 {@code t_access_log}：联系方式查看的<b>唯一</b>落点。
 *
 * <p><b>fail-closed</b>：调用方必须先成功写审计、再返回号码。插入抛异常时不吞，
 * 让请求以 500 结束 —— 宁可这次看不到号，也不要出现「号码已经泄露、审计里没有记录」。
 *
 * <p>未授权 / 403 / 404（记录已删）也写，只是 {@code result} 不同。pair 行本身不存在时
 * 无法写：{@code fk_access_pair} 要求 {@code pair_id} 指向真实行，没有可指的 id。
 */
@Service
public class AccessLogService {

    private static final Logger log = LoggerFactory.getLogger(AccessLogService.class);

    private final AccessLogMapper accessLogMapper;

    public AccessLogService(AccessLogMapper accessLogMapper) {
        this.accessLogMapper = accessLogMapper;
    }

    public void record(PairAccess access, AccessResult result, AuditMeta meta) {
        AccessLog entry = new AccessLog();
        entry.setPairId(access.pair().getId());
        entry.setViewerId(access.viewerId());
        entry.setViewerSide(access.viewerEnd().name());
        entry.setTargetSide(access.targetEnd().name());
        entry.setScore(access.pair().getScore());
        entry.setResult(result.name());
        entry.setRequestId(meta.requestId());
        entry.setIp(meta.ip());
        entry.setUserAgent(meta.userAgent());
        accessLogMapper.insert(entry);
        log.info("contact access audited pairId={} viewerId={} viewerSide={} targetSide={} result={}",
                entry.getPairId(), entry.getViewerId(), entry.getViewerSide(), entry.getTargetSide(),
                entry.getResult());
    }
}
