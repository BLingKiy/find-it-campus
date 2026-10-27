package com.findit.campus.pair.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.findit.campus.pair.domain.PairCountsRow;
import com.findit.campus.pair.mapper.PairFactsMapper;
import com.findit.campus.record.domain.MyPairCounts;
import com.findit.campus.record.service.PairFactsPort;

/**
 * {@link PairFactsPort} 的实现：把"记录上下文的问题"翻译成 {@code t_pair_score} 的只读查询。
 *
 * <p>依赖方向：{@code pair → record}（实现 record 声明的接口），record 不反向依赖 pair 的实现类；
 * record 只认 {@link PairFactsPort}，因此单测可以直接 mock 事实，不需要起数据库。
 *
 * <p>这里不做业务判断（是否 404、是否 403 都在 record 侧）。适配器只回答"数是多少"，
 * 判定规则集中在 {@code RecordVisibilityPolicy} 与 {@code RecordService} 一处。
 */
@Service
public class PairFactsAdapter implements PairFactsPort {

    private final PairFactsMapper pairFactsMapper;

    public PairFactsAdapter(PairFactsMapper pairFactsMapper) {
        this.pairFactsMapper = pairFactsMapper;
    }

    @Override
    public int countValidMatched(long recordId, LocalDateTime completedCutoff) {
        return pairFactsMapper.countValidMatched(recordId, completedCutoff);
    }

    @Override
    public boolean viewerHasQualifyingPair(long targetRecordId, long viewerId, BigDecimal scoreThreshold,
                                            LocalDateTime completedCutoff) {
        return pairFactsMapper.countQualifyingPairs(targetRecordId, viewerId, scoreThreshold, completedCutoff) > 0;
    }

    @Override
    public int countLowConfidence(long recordId, BigDecimal unlockThreshold, LocalDateTime completedCutoff) {
        return pairFactsMapper.countLowConfidence(recordId, unlockThreshold, completedCutoff);
    }

    @Override
    public Map<Long, MyPairCounts> countsOf(Collection<Long> recordIds, BigDecimal unlockThreshold,
                                             LocalDateTime completedCutoff) {
        if (recordIds == null || recordIds.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = recordIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return pairFactsMapper.countByRecordIds(ids, unlockThreshold, completedCutoff).stream()
                .collect(Collectors.toMap(PairCountsRow::getRecordId,
                        row -> new MyPairCounts(row.getTotal(), row.getRelated(),
                                row.getLowConfidence(), row.getMatched()),
                        (first, second) -> first, java.util.LinkedHashMap::new));
    }
}
