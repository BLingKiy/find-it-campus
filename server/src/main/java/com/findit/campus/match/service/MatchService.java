package com.findit.campus.match.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.findit.campus.category.service.CategoryService;
import com.findit.campus.common.api.ErrorCode;
import com.findit.campus.common.api.FieldError;
import com.findit.campus.common.config.MatchEngineConfig;
import com.findit.campus.common.error.BizException;
import com.findit.campus.common.masking.MaskingPolicy;
import com.findit.campus.match.domain.MatchResult;
import com.findit.campus.match.engine.MatchCandidate;
import com.findit.campus.match.engine.MatchCandidatePoolPort;
import com.findit.campus.match.engine.MatchCandidateSource;
import com.findit.campus.match.engine.MatchEngine;
import com.findit.campus.match.engine.MatchEngineException;
import com.findit.campus.match.engine.MatchQuery;
import com.findit.campus.match.mapper.MatchResultMapper;
import com.findit.campus.match.web.dto.MatchItemResponse;
import com.findit.campus.match.web.dto.MatchRequest;
import com.findit.campus.match.web.dto.MatchResponse;
import com.findit.campus.pair.config.PairPolicyProperties;
import com.findit.campus.record.domain.RecordSummary;
import com.findit.campus.record.domain.RecordType;
import com.findit.campus.record.mapper.RecordMapper;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * {@code /api/match} 的编排（契约 §8；{@code docs/ai-match-module.md} §5 的步骤顺序）。
 *
 * <p><b>只依赖 {@link MatchEngine} 这个抽象</b>：本类与 {@code MatchController} 里不出现任何实现类名
 * （验收 V1），换实现靠 {@code findit.match.engine} 一个配置值，本文件不改一行。
 *
 * <p>步骤与失败点：
 * <ol>
 *   <li>text 校验 → 30001 · type/topN/minScore → 10001（DTO 层）· categoryId → 40001</li>
 *   <li>取候选池（MATCHABLE：PENDING + 保留期内 COMPLETED；剔除 excludeRecordId）</li>
 *   <li>调引擎；{@code MatchEngineException} 带 30002/30003 原样上抛并补 {@code data.engine}</li>
 *   <li>回表补展示字段 <b>并过滤</b>：已删/CLOSED/刚过保留期的候选直接消失（ADR-008）</li>
 *   <li>先过滤后截断到 topN（顺序反了会在 topN=1 时返回一条已删记录）</li>
 *   <li>按身份遮蔽 title/location，{@code reason} 恒打码</li>
 *   <li>写快照：<b>写入前脱敏</b>（input_text 与 candidates 内文都过同一套打码）；失败只 warn</li>
 * </ol>
 *
 * <p>为什么快照要脱敏而不是"快照只给内部看"：{@code t_match_result} 是评估证据，会被导出、贴进文档、
 * 甚至交给第三方评估。输入文本里本来就可能含手机号/证件号，写库前不过 {@link MaskingPolicy}，
 * 等于给自己留了一份明文语料 —— 这正是 A1 H8 要挡的事。
 */
@Service
public class MatchService {

    private static final Logger log = LoggerFactory.getLogger(MatchService.class);

    private static final int TEXT_MIN_LENGTH = 2;
    private static final int TEXT_MAX_LENGTH = 2000;

    private final ObjectProvider<MatchEngine> engineProvider;
    private final MatchCandidatePoolPort candidatePool;
    private final RecordMapper recordMapper;
    private final MatchResultMapper matchResultMapper;
    private final CategoryService categoryService;
    private final MatchEngineConfig matchEngineConfig;
    private final PairPolicyProperties pairPolicy;
    private final ObjectMapper objectMapper;
    private final int poolSize;

    public MatchService(ObjectProvider<MatchEngine> engineProvider,
                        MatchCandidatePoolPort candidatePool,
                        RecordMapper recordMapper,
                        MatchResultMapper matchResultMapper,
                        CategoryService categoryService,
                        MatchEngineConfig matchEngineConfig,
                        PairPolicyProperties pairPolicy,
                        ObjectMapper objectMapper,
                        @Value("${findit.match.pool-size:500}") int poolSize) {
        this.engineProvider = engineProvider;
        this.candidatePool = candidatePool;
        this.recordMapper = recordMapper;
        this.matchResultMapper = matchResultMapper;
        this.categoryService = categoryService;
        this.matchEngineConfig = matchEngineConfig;
        this.pairPolicy = pairPolicy;
        this.objectMapper = objectMapper;
        this.poolSize = poolSize;
    }

    /** 兼容单测/内部调用的匿名视角入口（不传身份 = 遮蔽）。web 层必须走带 viewerId 的重载。 */
    public MatchResponse match(MatchRequest request) {
        return match(request, null);
    }

    /**
     * @param viewerId 当前会话用户；{@code null} = 匿名（本接口在门禁层要求登录，正常不会为 null）
     */
    public MatchResponse match(MatchRequest request, Long viewerId) {
        String text = requireText(request.getText());
        RecordType inputType = request.getType() == null ? null : RecordType.valueOf(request.getType());
        int topN = request.topNOrDefault();
        BigDecimal minScore = request.minScoreOrDefault();
        Long categoryId = request.getCategoryId();
        Long excludeRecordId = request.getExcludeRecordId();
        categoryService.requireExists(categoryId);

        MatchEngine engine = engineProvider.getIfAvailable();
        if (engine == null) {
            throw BizException.of(ErrorCode.MATCH_ENGINE_UNAVAILABLE,
                    "未装配匹配引擎实现（" + MatchEngineConfig.ENGINE_PROPERTY + "=" + matchEngineConfig.engine() + "）",
                    Map.of("engine", matchEngineConfig.engine()));
        }

        List<MatchCandidateSource> pool = candidatePool.load(inputType, categoryId, poolSize).stream()
                .filter(source -> excludeRecordId == null || !excludeRecordId.equals(source.recordId()))
                .toList();

        long startedAt = System.nanoTime();
        List<MatchCandidate> scored = invokeEngine(engine, pool, text, inputType, categoryId, topN, minScore);
        int costMs = (int) ((System.nanoTime() - startedAt) / 1_000_000L);

        List<MatchCandidate> candidates = filterAndRank(scored, minScore, topN, excludeRecordId);
        Map<Long, RecordSummary> summaries = loadSummaries(candidates);
        List<MatchItemResponse> items = candidates.stream()
                .filter(candidate -> summaries.containsKey(candidate.recordId()))
                .map(candidate -> {
                    RecordSummary summary = summaries.get(candidate.recordId());
                    boolean ownerView = viewerId != null && summary.getPublisherId() != null
                            && summary.getPublisherId().equals(viewerId);
                    return MatchItemResponse.of(candidate.recordId(), candidate.score(), candidate.reason(),
                            summary, ownerView);
                })
                .toList();

        MatchResponse response = new MatchResponse();
        response.setEngine(engine.impl());
        response.setInputType(inputType == null ? null : inputType.name());
        response.setTopN(topN);
        response.setMinScore(minScore);
        response.setTookMs(costMs);
        response.setTotal(items.size());
        response.setItems(items);
        response.setMatchId(writeSnapshot(text, inputType, categoryId, engine.impl(), topN, minScore, candidates, costMs));
        return response;
    }

    /**
     * text 校验：契约 §8.3 要求它是 {@code 30001} 而不是通用的 10001，因此放在这里而不是 DTO 注解上。
     * 仍然给 fieldErrors，前端能把错误落到"匹配输入框"上。
     */
    private static String requireText(String text) {
        String reason = null;
        if (text == null || text.isBlank()) {
            reason = "text 不得为空";
        } else if (text.length() < TEXT_MIN_LENGTH || text.length() > TEXT_MAX_LENGTH) {
            reason = "text 长度需在 " + TEXT_MIN_LENGTH + " 到 " + TEXT_MAX_LENGTH + " 之间";
        }
        if (reason != null) {
            throw BizException.of(ErrorCode.MATCH_INPUT_INVALID, reason,
                    Map.of("fieldErrors", List.of(new FieldError("text", reason))));
        }
        return text;
    }

    /**
     * 调引擎。{@code MatchEngineException} 本身已带 30002/30003，这里只补契约要求的
     * {@code data.engine}（§8.3）—— 错误码与 HTTP 状态仍由 {@code GlobalExceptionHandler} 统一映射。
     */
    private List<MatchCandidate> invokeEngine(MatchEngine engine, List<MatchCandidateSource> pool, String text,
                                              RecordType inputType, Long categoryId, int topN, BigDecimal minScore) {
        MatchQuery query = new MatchQuery(text, inputType, categoryId, topN, minScore);
        List<MatchCandidate> scored;
        try {
            scored = engine.match(pool, query);
        } catch (MatchEngineException e) {
            throw BizException.of(e.errorCode(), e.getMessage(), Map.of("engine", engine.impl()));
        }
        // 契约保证非 null，但引擎是可替换的外部组件 —— 拿 null 当空列表比 NPE 掉进 10005 好。
        return scored == null ? List.of() : scored;
    }

    /**
     * 过滤 → 稳定排序 → 截断（顺序不可换，见类注释）。
     *
     * <p>同分按 {@code recordId} 升序（§8.4）：保证同一输入两次调用结果可复现，
     * 否则 5.1 的 top1 断言会随机飘，而飘的原因会被误判成"算法不稳定"。
     */
    private static List<MatchCandidate> filterAndRank(List<MatchCandidate> scored, BigDecimal minScore, int topN,
                                                      Long excludeRecordId) {
        Set<Long> seen = new HashSet<>();
        List<MatchCandidate> valid = new ArrayList<>();
        for (MatchCandidate candidate : scored) {
            if (candidate == null || candidate.recordId() == null || candidate.score() == null) {
                continue;
            }
            if (excludeRecordId != null && excludeRecordId.equals(candidate.recordId())) {
                continue;
            }
            if (candidate.score().compareTo(minScore) < 0) {
                continue;
            }
            if (!seen.add(candidate.recordId())) {
                continue;
            }
            valid.add(candidate);
        }
        valid.sort(Comparator.comparing((MatchCandidate candidate) -> candidate.score(), Comparator.reverseOrder())
                .thenComparing(MatchCandidate::recordId));
        return valid.size() > topN ? List.copyOf(valid.subList(0, topN)) : valid;
    }

    /**
     * 回表补展示字段（ADR-008）。SQL 里再校验一次 MATCHABLE：取池与回表之间可能已经过了几十毫秒，
     * 期间记录可能已被删除、关闭，或刚跨过保留期边界。
     */
    private Map<Long, RecordSummary> loadSummaries(List<MatchCandidate> candidates) {
        List<Long> ids = candidates.stream()
                .map(MatchCandidate::recordId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        LocalDateTime cutoff = pairPolicy.completedCutoff(LocalDateTime.now());
        return recordMapper.selectSummariesByIds(ids, cutoff).stream()
                .collect(Collectors.toMap(RecordSummary::getId, Function.identity(), (first, second) -> first));
    }

    /**
     * 写快照（含空结果）。失败只 warn：快照是评估证据不是业务数据，
     * 不该让用户体验承担它的一致性（§8.5 / A9）。
     *
     * <p><b>写库前脱敏</b>（H8）：{@code input_text} 与 {@code candidates} 里的 {@code reason}
     * 都过 {@link MaskingPolicy#maskDescription}。快照的 candidates 与响应 items 同构但不含展示字段，
     * 因此不需要对 title/location 再打一遍码。
     *
     * @return 快照主键；写失败时 {@code null}（响应体的 {@code matchId} 随之为空，前端据此可感知"本次未留痕"）
     */
    private Long writeSnapshot(String text, RecordType inputType, Long categoryId, String impl, int topN,
                               BigDecimal minScore, List<MatchCandidate> candidates, int costMs) {
        try {
            MatchResult result = new MatchResult();
            result.setInputText(MaskingPolicy.maskDescription(text));
            result.setInputType(inputType == null ? null : inputType.name());
            result.setInputCategoryId(categoryId);
            result.setImpl(impl);
            result.setTopN(topN);
            result.setMinScore(minScore);
            result.setCandidates(objectMapper.writeValueAsString(toCandidateJson(candidates)));
            result.setCostMs(costMs);
            matchResultMapper.insert(result);
            return result.getId();
        } catch (Exception e) {
            log.warn("匹配快照写入失败（不影响本次响应）: {}", e.getMessage());
            return null;
        }
    }

    /** 快照里的候选与响应体 items 同构，只是不含展示字段（schema.sql 注释同口径）。reason 已脱敏。 */
    private static List<Map<String, Object>> toCandidateJson(List<MatchCandidate> candidates) {
        return candidates.stream()
                .map(candidate -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("recordId", candidate.recordId());
                    item.put("score", candidate.score());
                    item.put("reason", MaskingPolicy.maskDescription(candidate.reason()));
                    return item;
                })
                .toList();
    }
}
