package com.findit.campus.pair.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.findit.campus.match.engine.MatchCandidate;
import com.findit.campus.pair.config.PairPolicyProperties;
import com.findit.campus.pair.config.PairScoreProperties;
import com.findit.campus.pair.domain.PairSide;
import com.findit.campus.pair.engine.local.LocalKeywordPairScorer;
import com.findit.campus.record.domain.Record;
import com.findit.campus.record.domain.RecordCandidate;
import com.findit.campus.record.domain.RecordReviewState;
import com.findit.campus.record.domain.RecordStatus;
import com.findit.campus.record.domain.RecordType;
import com.findit.campus.record.mapper.RecordMapper;

/**
 * 召回：给一条记录挑出最多 {@code topN=10} 个"反向类型"的候选（架构 §5.1）。
 *
 * <p>三步，每一步都为了挡住一种具体的浪费：
 * <ol>
 *   <li><b>池</b>：一次 {@code MATCHABLE} 查询（{@code PENDING} + 保留期内 {@code COMPLETED}，
 *       排除 {@code CLOSED} 与已删除），上限 {@code pre-score-pool-size}。不把全表拉进来，
 *       因为召回成本必须随池上限而不是数据量增长。</li>
 *   <li><b>预排</b>：用本地关键词算法给整池打分，取前 {@code topN}。没有这一步，"最近 10 条"
 *       就是召回结果 —— 一条三天前的招领永远匹配不到今天发的寻物，而这正是失物招领最常见的场景。
 *       预排只用内存 CPU，不产生一次 LLM 调用。</li>
 *   <li><b>回表</b>：按 id 取完整原文（description/publisherId/categoryName），再复核一次
 *       {@code MATCHABLE}。预排与回表之间记录可能被关闭或删除，这一步把它们剔掉，
 *       避免为一个已失效的候选花一次 LLM 调用。</li>
 * </ol>
 *
 * <p>为什么不在召回阶段就定"最终分数"：召回是<b>粗排</b>，它的错误（漏掉一条本该匹配的记录）
 * 无法被后面的精排纠正。把 topN 从 10 放大到 100 能降低漏召回，但会让 LLM 成本涨 10 倍 ——
 * 这是一个明确的成本/召回权衡，当前取架构给定的 10，如需调整改 {@code findit.pair.score.top-n}。
 */
@Service
public class PairRecallService {

    private static final Logger log = LoggerFactory.getLogger(PairRecallService.class);

    private final RecordMapper recordMapper;
    private final LocalKeywordPairScorer localScorer;
    private final PairPolicyProperties policy;
    private final PairScoreProperties scoreProperties;

    public PairRecallService(RecordMapper recordMapper,
                             LocalKeywordPairScorer localScorer,
                             PairPolicyProperties policy,
                             PairScoreProperties scoreProperties) {
        this.recordMapper = recordMapper;
        this.localScorer = localScorer;
        this.policy = policy;
        this.scoreProperties = scoreProperties;
    }

    /**
     * @param source 触发打分的记录（已确认存在、未删除、未 CLOSED）
     * @return 反向类型候选，最多 {@code top-n} 条；无候选返回空列表（不是错误）
     */
    public List<PairSide> recall(PairSide source) {
        RecordType targetType = source.type().opposite();
        LocalDateTime cutoff = policy.completedCutoff(LocalDateTime.now());
        List<RecordCandidate> pool = recordMapper.selectCandidatePool(
                targetType, null, cutoff, scoreProperties.getPreScorePoolSize());
        if (pool.isEmpty()) {
            return List.of();
        }
        List<PairSide> candidates = new ArrayList<>(pool.size());
        for (RecordCandidate candidate : pool) {
            if (candidate.getId() == null || candidate.getId().equals(source.recordId())) {
                continue;
            }
            candidates.add(new PairSide(candidate.getId(), targetType, null, null,
                    candidate.getTitle(), candidate.getDescription(), candidate.getLocation(),
                    candidate.getCategoryId(), null, null, null));
        }
        if (candidates.isEmpty()) {
            return List.of();
        }

        List<Long> topIds = topIds(source, candidates);
        if (topIds.isEmpty()) {
            return List.of();
        }
        List<Record> hydrated = recordMapper.selectByIds(topIds);
        List<PairSide> recalled = new ArrayList<>(hydrated.size());
        for (Record record : hydrated) {
            if (!matches(record, cutoff)) {
                continue; // 预排到回表之间被关闭/删除/过保留期
            }
            recalled.add(toSide(record));
        }
        log.debug("pair recall sourceId={} type={} pool={} topN={} usable={}",
                source.recordId(), source.type(), candidates.size(), topIds.size(), recalled.size());
        return recalled;
    }

    /**
     * 预排结果按"分数降序、id 升序"取前 topN。
     *
     * <p>预排返回空（查询词全是虚词 / 引擎无命中）时退化为池顺序（{@code created_at DESC}）：
     * 有候选总比"一条都不打分"好 —— 召回为空会让用户看到"已出结果 0 条"，而实际上只是算法没词。
     */
    private List<Long> topIds(PairSide source, List<PairSide> candidates) {
        List<MatchCandidate> ranked = localScorer.rank(source, candidates);
        Set<Long> ids = new LinkedHashSet<>();
        if (ranked.isEmpty()) {
            for (int i = 0; i < candidates.size() && ids.size() < scoreProperties.getTopN(); i++) {
                ids.add(candidates.get(i).recordId());
            }
            return List.copyOf(ids);
        }
        for (MatchCandidate candidate : ranked) {
            if (ids.size() >= scoreProperties.getTopN()) {
                break;
            }
            if (candidate.recordId() != null) {
                ids.add(candidate.recordId());
            }
        }
        return List.copyOf(ids);
    }

    /**
     * MATCHABLE 复核（与 SQL 同口径）：未删除（SQL 已滤）+ 非 CLOSED + 保留期内的 COMPLETED。
     */
    private boolean matches(Record record, LocalDateTime cutoff) {
        if (record.getReviewState() != RecordReviewState.APPROVED) {
            return false;
        }
        if (record.getStatus() == RecordStatus.CLOSED) {
            return false;
        }
        if (record.getStatus() == RecordStatus.COMPLETED) {
            return record.getCompletedAt() != null && record.getCompletedAt().isAfter(cutoff);
        }
        return true;
    }

    /**
     * 打分快照走<b>比对通道</b>（{@code match_*}），不是展示通道：
     * 地点细节/时间/特征必须完整保留，否则区分度被削；历史行与 mode=off 回退到展示文本。
     */
    static PairSide toSide(Record record) {
        return new PairSide(record.getId(), record.getType(), record.getStatus(), record.getCompletedAt(),
                record.matchTitleOrDefault(), record.matchDescriptionOrDefault(),
                record.matchLocationOrDefault(),
                record.getCategoryId(), record.getCategoryName(), record.getOccurredAt(),
                record.getPublisherId());
    }
}
