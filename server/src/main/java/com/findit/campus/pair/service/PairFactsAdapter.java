package com.findit.campus.pair.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.findit.campus.pair.domain.ContactTier;
import com.findit.campus.pair.domain.PairCardRow;
import com.findit.campus.pair.domain.PairCountsRow;
import com.findit.campus.pair.mapper.PairFactsMapper;
import com.findit.campus.record.domain.MyPairCounts;
import com.findit.campus.record.domain.PairCardFacts;
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

    /**
     * 卡片装配：SQL 只给原始事实，三档/百分比/可用性/文案在这里用 pair 上下文的
     * {@link PairAccessPolicy} 算好，再交给 record 侧的 DTO 打码。
     *
     * <p>为什么不在 DTO 里算：{@code tier} 与 {@code contactHint} 的口径属于 pair 上下文，
     * record 侧反过来依赖 {@code pair.domain} 会破坏 {@link PairFactsPort} 建立的依赖方向
     * （record 只声明需要什么，pair 实现）。这里也是唯一能同时看到两端授权/确认时间的地方。
     *
     * <p>分组用 {@link LinkedHashMap}：SQL 已按 {@code score DESC, pair_id ASC} 排好，
     * 分组时保序，调用方拿到的就是契约要求的顺序，不需要再排一次。
     */
    @Override
    public Map<Long, List<PairCardFacts>> cardsOf(Collection<Long> recordIds,
                                                  BigDecimal unlockThreshold,
                                                  BigDecimal directThreshold,
                                                  LocalDateTime completedCutoff) {
        if (recordIds == null || recordIds.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = recordIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<PairCardFacts>> byRecord = new LinkedHashMap<>();
        for (PairCardRow row : pairFactsMapper.selectCardsByRecordIds(ids, unlockThreshold, completedCutoff)) {
            byRecord.computeIfAbsent(row.getRecordId(), key -> new ArrayList<>()).add(toFacts(row, unlockThreshold,
                    directThreshold));
        }
        return byRecord;
    }

    private static PairCardFacts toFacts(PairCardRow row, BigDecimal unlockThreshold, BigDecimal directThreshold) {
        boolean myLost = "LOST".equals(row.getMySide());
        boolean myConfirmed = (myLost ? row.getLostConfirmedAt() : row.getFoundConfirmedAt()) != null;
        boolean otherConfirmed = (myLost ? row.getFoundConfirmedAt() : row.getLostConfirmedAt()) != null;
        boolean myUnlocked = (myLost ? row.getLostUnlockedAt() : row.getFoundUnlockedAt()) != null;
        boolean otherUnlocked = (myLost ? row.getFoundUnlockedAt() : row.getLostUnlockedAt()) != null;
        ContactTier tier = PairAccessPolicy.tier(row.getScore(), directThreshold, unlockThreshold);
        String otherNote = myLost ? row.getFoundUnlockNote() : row.getLostUnlockNote();
        // 契约 §5.1：otherNote 仅 otherUnlocked 或 tier=DIRECT 时给，否则 null
        if (!otherUnlocked && tier != ContactTier.DIRECT) {
            otherNote = null;
        }
        boolean contactPresent = row.isCounterpartContactPresent();
        return new PairCardFacts(
                row.getRecordId(),
                row.getPairId(),
                row.getScore(),
                PairAccessPolicy.scorePercent(row.getScore()),
                tier.name(),
                row.getStatus(),
                "MATCHED".equals(row.getStatus()),
                myConfirmed,
                otherConfirmed,
                row.getLostConfirmedAt(),
                row.getFoundConfirmedAt(),
                row.getEngine(),
                "LOCAL_KEYWORD".equals(row.getEngine()),
                row.getMySide(),
                myUnlocked,
                otherUnlocked,
                myLost ? row.getLostUnlockNote() : row.getFoundUnlockNote(),
                otherNote,
                row.getCounterpartRecordId(),
                row.getCounterpartType(),
                row.getCounterpartTitle(),
                row.getCounterpartLocation(),
                row.getCounterpartOccurredAt(),
                row.getCounterpartCreatedAt(),
                row.getCounterpartStatus(),
                PairAccessPolicy.contactAvailable(tier, otherUnlocked, contactPresent),
                PairAccessPolicy.contactHint(tier, otherUnlocked, contactPresent));
    }
}
