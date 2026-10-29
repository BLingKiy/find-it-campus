package com.findit.campus.record.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.findit.campus.common.api.PageResult;
import com.findit.campus.pair.config.PairPolicyProperties;
import com.findit.campus.record.domain.MyPairCounts;
import com.findit.campus.record.domain.MyRecordRow;
import com.findit.campus.record.domain.PairCardFacts;
import com.findit.campus.record.domain.RecordSortField;
import com.findit.campus.record.domain.RecordStatus;
import com.findit.campus.record.domain.RecordType;
import com.findit.campus.record.domain.SortDirection;
import com.findit.campus.record.mapper.MyRecordCondition;
import com.findit.campus.record.mapper.RecordMapper;
import com.findit.campus.record.web.dto.MyRecordListItemResponse;
import com.findit.campus.record.web.dto.MyRecordsQuery;
import com.findit.campus.record.web.dto.PairCard;

/**
 * 「我的启事」（契约 §4.12）：所有者管理视图，三态都返回。
 *
 * <p>安全边界有两条，都在 SQL/service 里强绑，不接受请求参数：
 * <ol>
 *   <li>{@code publisher_id = session.userId} —— 服务端从会话取，不读请求里的任何 id</li>
 *   <li>本人数据回显<b>不遮蔽</b>（contact/publisherName/location/title 原文）——
 *       这是本人数据回显，不是跨用户泄露；正是「只做输出层遮蔽、DB 保留明文」的直接结果</li>
 * </ol>
 *
 * <p>计数与卡片<b>各一次批量查询</b>（{@link PairFactsPort#countsOf} /
 * {@link PairFactsPort#cardsOf}）而不是逐行查：一页最多 50 行，逐行往返会把列表接口的尾延迟
 * 拖成倍数，也会给 DB 制造 50 倍的压力。查询条数与页大小无关。
 */
@Service
public class MyRecordService {

    private static final Logger log = LoggerFactory.getLogger(MyRecordService.class);

    private static final String STATUS_ALL = "ALL";

    private final RecordMapper recordMapper;
    private final PairFactsPort pairFactsPort;
    private final PairPolicyProperties pairPolicy;

    public MyRecordService(RecordMapper recordMapper,
                           PairFactsPort pairFactsPort,
                           PairPolicyProperties pairPolicy) {
        this.recordMapper = recordMapper;
        this.pairFactsPort = pairFactsPort;
        this.pairPolicy = pairPolicy;
    }

    public PageResult<MyRecordListItemResponse> list(MyRecordsQuery query, long publisherId) {
        RecordStatus status = parseStatus(query.statusOrDefault());
        MyRecordCondition condition = MyRecordCondition.of(
                publisherId,
                parseType(query.getType()),
                status,
                RecordSortField.parse(query.sortByOrDefault()),
                SortDirection.parse(query.sortOrderOrDefault()));

        int page = query.pageOrDefault();
        int pageSize = query.pageSizeOrDefault();
        long total = recordMapper.countByPublisher(condition);
        if (total == 0) {
            return PageResult.of(page, pageSize, 0, List.of());
        }

        List<MyRecordRow> rows = recordMapper.selectMyRecords(condition, (page - 1) * pageSize, pageSize);
        List<Long> recordIds = rows.stream().map(MyRecordRow::getId).toList();
        // 同一个 cutoff 同时给计数与卡片：两处若各算一次 now，保留期边界上会出现"计数 1、卡片 0"
        LocalDateTime cutoff = pairPolicy.completedCutoff(LocalDateTime.now());
        Map<Long, MyPairCounts> counts = pairFactsPort.countsOf(recordIds, cutoff);
        Map<Long, List<PairCardFacts>> cards = pairFactsPort.cardsOf(recordIds, cutoff);

        List<MyRecordListItemResponse> items = rows.stream()
                .map(row -> MyRecordListItemResponse.of(
                        row,
                        counts.getOrDefault(row.getId(), MyPairCounts.EMPTY),
                        cardsOf(row.getId(), cards)))
                .toList();
        log.debug("我的启事 publisherId={} status={} total={}", publisherId, query.statusOrDefault(), total);
        return PageResult.of(page, pageSize, total, items);
    }

    /** 无卡片时给空列表（契约 §4.12：{@code relatedCount=0} 时 {@code pairs=[]}，不是省略）。 */
    private static List<PairCard> cardsOf(Long recordId, Map<Long, List<PairCardFacts>> byRecord) {
        return byRecord.getOrDefault(recordId, List.of()).stream()
                .map(PairCard::of)
                .toList();
    }

    /** {@code ALL} → null（SQL 不加状态条件）；其余三态直接映射。 */
    private static RecordStatus parseStatus(String status) {
        return STATUS_ALL.equalsIgnoreCase(status) ? null : RecordStatus.valueOf(status);
    }

    private static RecordType parseType(String type) {
        return type == null ? null : RecordType.valueOf(type);
    }
}
