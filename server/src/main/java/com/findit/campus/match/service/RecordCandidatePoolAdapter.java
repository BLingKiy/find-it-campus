package com.findit.campus.match.service;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.findit.campus.match.engine.MatchCandidatePoolPort;
import com.findit.campus.match.engine.MatchCandidateSource;
import com.findit.campus.pair.config.PairPolicyProperties;
import com.findit.campus.record.domain.RecordCandidate;
import com.findit.campus.record.domain.RecordType;
import com.findit.campus.record.mapper.RecordMapper;

/**
 * {@link MatchCandidatePoolPort} 的 record 侧实现：把"候选池"翻译成一次 {@code t_record} 的只读查询。
 *
 * <p>为什么这个实现落在 record 侧而不是引擎实现里：它做的不是"算法"而是"取数"，
 * 里面装着业务不变量（未逻辑删除、{@code MATCHABLE}）—— 这些属于 record 上下文（§3），
 * 让每个引擎实现各写一遍，等于把业务规则复制到每个算法实现里（ADR-008 正是要挡这件事）。
 *
 * <p><b>C1 起候选池是 MATCHABLE 而不是 PENDING</b>：保留期内的 {@code COMPLETED} 仍要参与召回
 * （列表隐藏 ≠ 匹配隐藏），{@code CLOSED} 与已删除一律排除。保留期下界从
 * {@link PairPolicyProperties} 取，SQL 里不出 30 这类魔数。
 *
 * <p>依赖方向：{@code match → record.mapper}，record 侧不认识引擎的任何类型，
 * 转换（{@link RecordCandidate} → {@link MatchCandidateSource}）发生在这里。
 */
@Component
public class RecordCandidatePoolAdapter implements MatchCandidatePoolPort {

    private final RecordMapper recordMapper;
    private final PairPolicyProperties pairPolicy;
    private final int maxPoolSize;

    public RecordCandidatePoolAdapter(RecordMapper recordMapper,
                                      PairPolicyProperties pairPolicy,
                                      @Value("${findit.match.pool-size:500}") int maxPoolSize) {
        this.recordMapper = recordMapper;
        this.pairPolicy = pairPolicy;
        this.maxPoolSize = maxPoolSize;
    }

    @Override
    public List<MatchCandidateSource> load(RecordType targetType, Long categoryId, int limit) {
        // 上限在这里兜底而不是信调用方：候选池大小直接决定打分成本，一次误传 10 万就能把请求拖垮。
        int size = limit <= 0 ? maxPoolSize : Math.min(limit, maxPoolSize);
        return recordMapper.selectCandidatePool(targetType, categoryId,
                        pairPolicy.completedCutoff(java.time.LocalDateTime.now()), size).stream()
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
