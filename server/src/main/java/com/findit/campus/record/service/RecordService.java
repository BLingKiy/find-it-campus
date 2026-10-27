package com.findit.campus.record.service;

import java.time.LocalDateTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.findit.campus.auth.domain.SessionUser;
import com.findit.campus.category.service.CategoryService;
import com.findit.campus.common.api.ErrorCode;
import com.findit.campus.common.api.PageResult;
import com.findit.campus.common.api.RequestContext;
import com.findit.campus.common.error.BizException;
import com.findit.campus.pair.config.PairPolicyProperties;
import com.findit.campus.pair.event.PairScoringRequested;
import com.findit.campus.record.domain.Record;
import com.findit.campus.record.domain.RecordSortField;
import com.findit.campus.record.domain.RecordStatus;
import com.findit.campus.record.domain.RecordType;
import com.findit.campus.record.domain.SortDirection;
import com.findit.campus.record.mapper.RecordCondition;
import com.findit.campus.record.mapper.RecordMapper;
import com.findit.campus.record.service.RecordVisibilityPolicy.DetailAccess;
import com.findit.campus.record.web.dto.RecordCreateRequest;
import com.findit.campus.record.web.dto.RecordDetailResponse;
import com.findit.campus.record.web.dto.RecordListItemResponse;
import com.findit.campus.record.web.dto.RecordQuery;
import com.findit.campus.record.web.dto.StatusUpdateRequest;
import com.findit.campus.record.web.dto.StatusUpdateResponse;

/**
 * 记录上下文的业务规则与事务边界（契约 §4）。
 *
 * <p>本类在 v2 C1 承担三条边界，全部集中在这里，controller 不允许重复：
 * <ol>
 *   <li><b>身份</b>：读接口接收 {@code viewerId}（匿名 null），写接口必须非 null；
 *       所有权一律 {@code publisher_id == session.userId}，不认请求体、不认展示名。</li>
 *   <li><b>可见性</b>：详情/写操作先走 {@link RecordVisibilityPolicy}，终态与"不合格对端"统一 404；
 *       "可见但非所有者"才 403。两码分流，避免 CLOSED 记录被 403 探出存在性。</li>
 *   <li><b>状态写入路径</b>：{@code t_record.status} 只有两类写：POST 初始化 PENDING、
 *       PATCH 由所有者在本类调用 {@link RecordMapper#markCompleted}/{@link RecordMapper#close}。
 *       系统任务（打分/扫描/通知）不依赖本类的任何写方法 —— 本类也没有"系统改状态"的方法。</li>
 * </ol>
 *
 * <p>409 的零写入保证：{@code PENDING→COMPLETED} 之前先查有效 MATCHED pair，
 * 不满足直接抛 20005；事务里没有任何 DML，因此 status/completed_at/updated_at 三个值都不变。
 */
@Service
public class RecordService {

    private static final Logger log = LoggerFactory.getLogger(RecordService.class);

    /** 契约 §4.1：公共列表可见集合恒为 PENDING；请求里的 COMPLETED/CLOSED 只返回空列表。 */
    private static final String STATUS_ALL = "ALL";
    private static final String STATUS_PENDING = "PENDING";

    private final RecordMapper recordMapper;
    private final CategoryService categoryService;
    private final PairFactsPort pairFactsPort;
    private final PairPolicyProperties pairPolicy;
    private final ApplicationEventPublisher eventPublisher;

    public RecordService(RecordMapper recordMapper,
                         CategoryService categoryService,
                         PairFactsPort pairFactsPort,
                         PairPolicyProperties pairPolicy,
                         ApplicationEventPublisher eventPublisher) {
        this.recordMapper = recordMapper;
        this.categoryService = categoryService;
        this.pairFactsPort = pairFactsPort;
        this.pairPolicy = pairPolicy;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 公共列表（契约 §4.1）：只返回 {@code PENDING}，逐条按身份遮蔽；空结果是 200 + 空列表。
     *
     * <p>请求 {@code status=COMPLETED/CLOSED} 时直接返回空页，<b>不把它翻译成 SQL 条件</b>：
     * 公共列表的结构性不变量是"恒 PENDING"，允许调用方通过参数改变可见集合，等于把
     * "终态记录也会出现在公共列表"变成一个只差一个参数的开关。
     */
    public PageResult<RecordListItemResponse> list(RecordQuery query, Long viewerId) {
        if (query.getCategoryId() != null) {
            categoryService.requireSelectable(query.getCategoryId());
        }
        String requestedStatus = query.statusOrDefault();
        if (!STATUS_ALL.equalsIgnoreCase(requestedStatus)
                && !STATUS_PENDING.equalsIgnoreCase(requestedStatus)) {
            return PageResult.of(query.pageOrDefault(), query.pageSizeOrDefault(), 0, List.of());
        }

        RecordCondition condition = RecordCondition.of(
                parseType(query.getType()),
                RecordStatus.PENDING,
                query.getCategoryId(),
                query.getKeyword(),
                RecordSortField.parse(query.sortByOrDefault()),
                SortDirection.parse(query.sortOrderOrDefault()));

        int page = query.pageOrDefault();
        int pageSize = query.pageSizeOrDefault();
        long total = recordMapper.countByCondition(condition);
        List<RecordListItemResponse> items = List.of();
        if (total > 0) {
            items = recordMapper.selectSummaries(condition, (page - 1) * pageSize, pageSize).stream()
                    .map(summary -> RecordListItemResponse.from(summary, viewerId))
                    .toList();
        }
        return PageResult.of(page, pageSize, total, items);
    }

    /**
     * 详情（契约 §4.2）：按 {@link RecordVisibilityPolicy} 判定三档。
     *
     * <p>合格对端判定只在"COMPLETED + 登录 + 非所有者"时查库：其余分支的答案与 pair 无关，
     * 多查一次只是给每个匿名详情请求多一次 IO。
     */
    public RecordDetailResponse detail(long id, Long viewerId) {
        Record record = requireRecord(id);
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime cutoff = pairPolicy.completedCutoff(now);
        // 所有者对 COMPLETED 本来就 404（A9 字面），不必为它查 pair；合格对端判定也只对 COMPLETED 有意义。
        boolean owner = RecordVisibilityPolicy.isOwner(record, viewerId);
        boolean qualifyingPeer = !owner
                && record.getStatus() == RecordStatus.COMPLETED
                && viewerId != null
                && pairFactsPort.viewerHasQualifyingPair(id, viewerId,
                        pairPolicy.getCompletedVisibleThreshold(), cutoff);
        DetailAccess access = RecordVisibilityPolicy.detailAccess(record, viewerId, qualifyingPeer,
                pairPolicy.getCompletedRetentionDays(), now);
        if (access == DetailAccess.NOT_FOUND) {
            throw BizException.of(ErrorCode.RECORD_NOT_FOUND);
        }
        if (access == DetailAccess.OWNER_PLAINTEXT) {
            return RecordDetailResponse.ownerView(record,
                    pairFactsPort.countValidMatched(id, cutoff),
                    pairFactsPort.countLowConfidence(id, pairPolicy.getContactUnlockThreshold(), cutoff));
        }
        return RecordDetailResponse.from(record);
    }

    /** 提交（契约 §4.3）：publisher_id 取 session，客户端传值被忽略；status 固定 PENDING。 */
    @Transactional
    public RecordDetailResponse create(RecordCreateRequest request, SessionUser publisher) {
        categoryService.requireSelectable(request.getCategoryId());

        Record record = new Record();
        record.setType(RecordType.valueOf(request.getType()));
        record.setStatus(RecordStatus.PENDING);
        record.setTitle(request.getTitle());
        record.setDescription(request.getDescription());
        record.setCategoryId(request.getCategoryId());
        record.setLocation(request.getLocation());
        record.setOccurredAt(request.occurredAtAsDateTime());
        record.setContact(request.getContact());
        record.setPublisherName(request.getPublisherName() == null
                ? publisher.displayName()
                : request.getPublisherName());
        record.setPublisherId(publisher.id());

        recordMapper.insert(record);
        log.info("新增记录 id={} type={} publisherId={}", record.getId(), record.getType(), publisher.id());
        publishPairScoring(record.getId(), "publish");
        return RecordDetailResponse.ownerView(requireRecord(record.getId()), 0, 0);
    }

    /**
     * 全量修改（契约 §4.4）：非所有者先按可见性分流 403/404；所有者终态 409/20003。
     *
     * <p>顺序不可换：先 404（不存在/不可见）再 403（可见但非所有者）最后 409（所有者但状态不允许），
     * 否则 CLOSED 记录会先命中 20003，等于告诉非所有者"这条记录存在且已关闭"。
     */
    @Transactional
    public RecordDetailResponse update(long id, RecordCreateRequest request, Long viewerId) {
        Record existing = requireRecord(id);
        LocalDateTime now = LocalDateTime.now();
        if (!RecordVisibilityPolicy.isOwner(existing, viewerId)) {
            throw writeAccessDenied(existing, viewerId, now);
        }
        if (existing.getStatus() != RecordStatus.PENDING) {
            throw BizException.of(ErrorCode.RECORD_OPERATION_NOT_ALLOWED,
                    "已" + existing.getStatus() + "的记录不可再修改内容");
        }
        categoryService.requireSelectable(request.getCategoryId());

        Record record = new Record();
        record.setId(id);
        record.setType(RecordType.valueOf(request.getType()));
        record.setTitle(request.getTitle());
        record.setDescription(request.getDescription());
        record.setCategoryId(request.getCategoryId());
        record.setLocation(request.getLocation());
        record.setOccurredAt(request.occurredAtAsDateTime());
        record.setContact(request.getContact());
        record.setPublisherName(request.getPublisherName());

        if (recordMapper.updateContent(record) == 0) {
            throw BizException.of(ErrorCode.RECORD_NOT_FOUND);
        }
        log.info("修改记录 id={} publisherId={}", id, viewerId);
        // 契约 §4.4：提交后触发该记录相关 pair 重算；不清空已有 pair 的 status/授权/附言/confirmed_at
        publishPairScoring(id, "update");
        LocalDateTime cutoff = pairPolicy.completedCutoff(now);
        return RecordDetailResponse.ownerView(requireRecord(id),
                pairFactsPort.countValidMatched(id, cutoff),
                pairFactsPort.countLowConfidence(id, pairPolicy.getContactUnlockThreshold(), cutoff));
    }

    /**
     * 三态流转（契约 §4.5）：<b>唯一</b>的 status 写入路径。
     *
     * <p>先判可见/所有权，再走状态图（20002），最后判完成前置（20005）。
     * 完成前置检查在 UPDATE 之前，失败时事务里零 DML。
     */
    @Transactional
    public StatusUpdateResponse updateStatus(long id, StatusUpdateRequest request, Long viewerId) {
        Record existing = requireRecord(id);
        LocalDateTime now = LocalDateTime.now();
        if (!RecordVisibilityPolicy.isOwner(existing, viewerId)) {
            throw writeAccessDenied(existing, viewerId, now);
        }

        RecordStatus target = RecordStatus.valueOf(request.getStatus());
        RecordStatusPolicy.requireTransition(existing.getStatus(), target);

        LocalDateTime cutoff = pairPolicy.completedCutoff(now);
        int matchedPairCount = 0;
        if (target == RecordStatus.COMPLETED) {
            // 零写入关键点：不满足就直接抛 20005，下面没有任何 DML
            matchedPairCount = pairFactsPort.countValidMatched(id, cutoff);
            RecordStatusPolicy.requireCompletable(matchedPairCount);
        }

        int affected = target == RecordStatus.COMPLETED
                ? recordMapper.markCompleted(id, now)
                : recordMapper.close(id, now);
        if (affected == 0) {
            // 上面刚读过：走到这里只可能是并发删除，或状态已被另一个请求改走
            Record current = recordMapper.selectById(id);
            if (current == null) {
                throw BizException.of(ErrorCode.RECORD_NOT_FOUND);
            }
            RecordStatusPolicy.requireTransition(current.getStatus(), target);
            throw BizException.of(ErrorCode.RECORD_ILLEGAL_STATUS_TRANSITION,
                    "记录状态已被并发修改", RecordStatusPolicy.transitionErrorData(current.getStatus()));
        }
        log.info("记录状态流转 id={} {} -> {} publisherId={}", id, existing.getStatus(), target, viewerId);
        return StatusUpdateResponse.of(requireRecord(id), matchedPairCount);
    }

    /** 逻辑删除（契约 §4.6）：三态都可删，只写 deleted_at/updated_at，不写 status。 */
    @Transactional
    public void delete(long id, Long viewerId) {
        Record existing = requireRecord(id);
        LocalDateTime now = LocalDateTime.now();
        if (!RecordVisibilityPolicy.isOwner(existing, viewerId)) {
            throw writeAccessDenied(existing, viewerId, now);
        }
        if (recordMapper.markDeleted(id, now) == 0) {
            throw BizException.of(ErrorCode.RECORD_NOT_FOUND);
        }
        log.info("逻辑删除记录 id={} publisherId={}", id, viewerId);
    }

    /**
     * 写路径的"可见但非所有者"出口：可见（PENDING，或 COMPLETED 的合格对端）→ 403；
     * 不可见（CLOSED/到期/非合格对端）→ 404。匿名在门禁层已被 401 拦住，这里再兜一层 403。
     */
    private BizException writeAccessDenied(Record record, Long viewerId, LocalDateTime now) {
        LocalDateTime cutoff = pairPolicy.completedCutoff(now);
        boolean qualifyingPeer = record.getStatus() == RecordStatus.COMPLETED
                && viewerId != null
                && pairFactsPort.viewerHasQualifyingPair(record.getId(), viewerId,
                        pairPolicy.getCompletedVisibleThreshold(), cutoff);
        DetailAccess access = RecordVisibilityPolicy.detailAccess(record, viewerId, qualifyingPeer,
                pairPolicy.getCompletedRetentionDays(), now);
        if (access == DetailAccess.NOT_FOUND) {
            return BizException.of(ErrorCode.RECORD_NOT_FOUND);
        }
        return BizException.of(ErrorCode.FORBIDDEN);
    }

    /**
     * 提交后触发配对打分（D1）。<b>只发事件，不调打分服务</b>：
     *
     * <p>① 事件由 {@code @TransactionalEventListener(AFTER_COMMIT)} 接收，因此打分永远发生在
     * 本方法的事务提交之后 —— 别的连接那时才读得到新行；② 监听器再 {@code @Async} 到专用线程池，
     * 发布请求<b>不等待</b>召回与 LLM（任务书要求，也是 P99<500ms 的前提）。
     *
     * <p>为什么把 {@code requestId} 塞进事件：异步线程没有 MDC，日志会断链；
     * 带上之后"发布 → 打分"两条日志可以用同一个 id 串起来（架构 §12.3）。
     *
     * <p>事件发布本身不会失败（Spring 的同步事件总线），即使没有监听器也只是空转 ——
     * 所以这里不需要 try/catch，任何异常都说明是容器坏了。
     */
    private void publishPairScoring(long recordId, String trigger) {
        eventPublisher.publishEvent(new PairScoringRequested(recordId, trigger, RequestContext.requestId()));
    }

    /**
     * 取一条"存在且未删除"的记录，否则 20001。
     *
     * <p>读路径的 {@code deleted_at IS NULL} 在 SQL 里（ADR-006），这里只对结果做判空，
     * 不在 Java 里再判一次 deletedAt —— 两处判断一旦不一致，就会出现"能读详情但不能改"的怪状态。
     */
    private Record requireRecord(long id) {
        Record record = recordMapper.selectById(id);
        if (record == null) {
            throw BizException.of(ErrorCode.RECORD_NOT_FOUND);
        }
        return record;
    }

    private static RecordType parseType(String type) {
        return type == null ? null : RecordType.valueOf(type);
    }
}
