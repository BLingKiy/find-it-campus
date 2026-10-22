package com.findit.campus.record.service;

import java.time.LocalDateTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.findit.campus.category.service.CategoryService;
import com.findit.campus.common.api.ErrorCode;
import com.findit.campus.common.api.PageResult;
import com.findit.campus.common.error.BizException;
import com.findit.campus.record.domain.Record;
import com.findit.campus.record.domain.RecordSortField;
import com.findit.campus.record.domain.RecordStatus;
import com.findit.campus.record.domain.RecordType;
import com.findit.campus.record.domain.SortDirection;
import com.findit.campus.record.mapper.RecordCondition;
import com.findit.campus.record.mapper.RecordMapper;
import com.findit.campus.record.web.dto.RecordCreateRequest;
import com.findit.campus.record.web.dto.RecordDetailResponse;
import com.findit.campus.record.web.dto.RecordListItemResponse;
import com.findit.campus.record.web.dto.RecordQuery;
import com.findit.campus.record.web.dto.StatusUpdateRequest;
import com.findit.campus.record.web.dto.StatusUpdateResponse;

/**
 * 记录上下文的业务规则与事务边界（§6：事务只标在写方法；读方法不加事务，避免无谓的长连接占用）。
 *
 * <p>三类"不存在"统一翻译成 {@code 20001}：查不到 / 已逻辑删除 / 并发下被别人先删。
 * 写路径用"影响行数 = 0"判定而不是"先 SELECT 再 UPDATE"，少一次往返也少一个 TOCTOU 窗口。
 *
 * <p>几个取基线默认值的判断点（<b>1.1 未产出，绑定待拍板项</b>，改这些是改常量不是改结构）：
 * <ul>
 *   <li>新建记录初始状态 {@code PENDING}（§4 不变量）</li>
 *   <li>{@code CLOSED} 不允许再改内容 → {@code 20003}（契约 §6）</li>
 *   <li>任意状态都允许逻辑删除（Q-03 推荐值；契约 §6.2「基线先允许任何状态删除」）</li>
 * </ul>
 */
@Service
public class RecordService {

    private static final Logger log = LoggerFactory.getLogger(RecordService.class);

    /** 契约 §3.1：{@code status=ALL} 表示不限状态；SQL 里用 {@code null} 表达，不存在 "ALL" 这个状态值。 */
    private static final String STATUS_ALL = "ALL";

    private final RecordMapper recordMapper;
    private final CategoryService categoryService;

    public RecordService(RecordMapper recordMapper, CategoryService categoryService) {
        this.recordMapper = recordMapper;
        this.categoryService = categoryService;
    }

    /**
     * 列表（筛选 + 分页 + 排序，契约 §3）。
     *
     * <p>空结果是正常结果：{@code total=0} 时 {@link PageResult} 自己会给出
     * {@code totalPages=0 / list=[] / hasNext=false}，接口仍返回 200 + code 0（契约 §1「空结果」）。
     */
    public PageResult<RecordListItemResponse> list(RecordQuery query) {
        if (query.getCategoryId() != null) {
            categoryService.requireSelectable(query.getCategoryId());
        }
        RecordCondition condition = RecordCondition.of(
                parseType(query.getType()),
                parseStatus(query.statusOrDefault()),
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
                    .map(RecordListItemResponse::from)
                    .toList();
        }
        return PageResult.of(page, pageSize, total, items);
    }

    /** 详情（契约 §4）。不存在或已删除 → 20001。 */
    public RecordDetailResponse detail(long id) {
        return RecordDetailResponse.from(requireRecord(id));
    }

    /** 提交新记录（契约 §5）。非幂等：重复提交产生两条记录（契约 §5.2 明确接受）。 */
    @Transactional
    public RecordDetailResponse create(RecordCreateRequest request) {
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
        record.setPublisherName(request.getPublisherName());

        recordMapper.insert(record);
        log.info("新增记录 id={} type={}", record.getId(), record.getType());
        // 回读而不是手拼返回体：created_at / updated_at 是 DB 默认值，手拼就会与库里差几毫秒甚至差一天。
        return RecordDetailResponse.from(requireRecord(record.getId()));
    }

    /**
     * 全量修改内容（契约 §6）。
     *
     * <p>语义是"请求体即最终状态"：未传的可选字段按 {@code null} 覆盖。契约要求前端先 GET 详情再回填表单，
     * 服务端不做"部分更新"的猜测 —— 半更新的最大问题是调用方无法预测哪个字段会被保留。
     */
    @Transactional
    public RecordDetailResponse update(long id, RecordCreateRequest request) {
        Record existing = requireRecord(id);
        if (existing.getStatus() == RecordStatus.CLOSED) {
            throw BizException.of(ErrorCode.RECORD_OPERATION_NOT_ALLOWED, "已关闭的记录不可再修改内容");
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
            // 上面刚读过，走到这里说明并发下被删除了 —— 同样按 20001 报，不静默返回成功。
            throw BizException.of(ErrorCode.RECORD_NOT_FOUND);
        }
        log.info("修改记录 id={}", id);
        return RecordDetailResponse.from(requireRecord(id));
    }

    /** 状态流转（契约 §6.1）。非法流转由 {@link RecordStatusPolicy} 抛 20002（409）。 */
    @Transactional
    public StatusUpdateResponse updateStatus(long id, StatusUpdateRequest request) {
        Record existing = requireRecord(id);
        RecordStatus target = RecordStatus.valueOf(request.getStatus());
        RecordStatusPolicy.requireTransition(existing.getStatus(), target);

        if (recordMapper.updateStatus(id, target) == 0) {
            throw BizException.of(ErrorCode.RECORD_NOT_FOUND);
        }
        log.info("记录状态流转 id={} {} -> {}", id, existing.getStatus(), target);
        return StatusUpdateResponse.of(id, target);
    }

    /**
     * 逻辑删除（契约 §6.2）。
     *
     * <p>已删除再删 → 20001（契约明确选择"让调用方知道目标已不存在"，而不是假装成功）。
     * 任意状态允许删除取 Q-03 的推荐值，1.1 若收窄，改这里的一处判断即可。
     */
    @Transactional
    public void delete(long id) {
        if (recordMapper.markDeleted(id, LocalDateTime.now()) == 0) {
            throw BizException.of(ErrorCode.RECORD_NOT_FOUND);
        }
        log.info("逻辑删除记录 id={}", id);
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

    private static RecordStatus parseStatus(String status) {
        return STATUS_ALL.equalsIgnoreCase(status) ? null : RecordStatus.valueOf(status);
    }
}
