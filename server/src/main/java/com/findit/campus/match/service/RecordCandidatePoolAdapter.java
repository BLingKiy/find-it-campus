package com.findit.campus.match.service;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.findit.campus.match.engine.MatchCandidatePoolPort;
import com.findit.campus.match.engine.MatchCandidateSource;
import com.findit.campus.record.domain.RecordCandidate;
import com.findit.campus.record.domain.RecordType;
import com.findit.campus.record.mapper.RecordMapper;

/**
 * {@link MatchCandidatePoolPort} 的 record 侧实现：把"候选池"翻译成一次 {@code t_record} 的只读查询。
 *
 * <p>为什么这个实现落在 3.1 的 record 侧而不是留给 3.2：它做的不是"算法"而是"取数"，
 * 里面装着两条业务不变量（未逻辑删除、{@code status=PENDING}）—— 这两条属于 record 上下文（§3），
 * 让 3.2 的引擎实现去写，等于把业务规则复制到每个算法实现里（ADR-008 正是要挡这件事）。
 * 3.2 只需实现 {@code MatchEngine.match}。
 *
 * <p>依赖方向：{@code match → record.mapper}，record 侧不认识引擎的任何类型，
 * 转换（{@link RecordCandidate} → {@link MatchCandidateSource}）发生在这里。
 */
@Component
public class RecordCandidatePoolAdapter implements MatchCandidatePoolPort {

    private final RecordMapper recordMapper;
    private final int maxPoolSize;

    public RecordCandidatePoolAdapter(RecordMapper recordMapper,
                                      @Value("${findit.match.pool-size:500}") int maxPoolSize) {
        this.recordMapper = recordMapper;
        this.maxPoolSize = maxPoolSize;
    }

    @Override
    public List<MatchCandidateSource> load(RecordType targetType, Long categoryId, int limit) {
        // 上限在这里兜底而不是信调用方：候选池大小直接决定打分成本，一次误传 10 万就能把请求拖垮。
        int size = limit <= 0 ? maxPoolSize : Math.min(limit, maxPoolSize);
        return recordMapper.selectCandidatePool(targetType, categoryId, size).stream()
                .map(this::toSource)
                .toList();
    }

    private MatchCandidateSource toSource(RecordCandidate candidate) {
        return new MatchCandidateSource(
                candidate.getId(),
                candidate.getTitle(),
                candidate.getDescription(),
                candidate.getCategoryId(),
                candidate.getLocation());
    }
}
