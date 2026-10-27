package com.findit.campus.pair.service;

import org.springframework.stereotype.Service;

import com.findit.campus.pair.domain.PairLookup;
import com.findit.campus.pair.domain.PairScore;
import com.findit.campus.pair.mapper.PairScoreMapper;
import com.findit.campus.record.domain.Record;
import com.findit.campus.record.mapper.RecordMapper;

/**
 * 按 pairId 装配 {@link PairLookup}：pair 一行 + 两端记录各一行。
 *
 * <p>为什么单独成类：三个 E1 接口（contact / contact-unlock / PATCH pair）都要这三行，
 * 而且都要按<b>同一顺序</b>判「pair 不存在 / 记录已删」。装配与判定分开后，
 * 「记录已删返回 null」这个 MyBatis 事实只在一个地方被解释成 404。
 *
 * <p>{@link RecordMapper#selectById} 恒带 {@code deleted_at IS NULL}，所以这里拿到的 null
 * 只可能是「记录被逻辑删除」—— pair 外键保证了行存在。
 */
@Service
public class PairLookupService {

    private final PairScoreMapper pairScoreMapper;
    private final RecordMapper recordMapper;

    public PairLookupService(PairScoreMapper pairScoreMapper, RecordMapper recordMapper) {
        this.pairScoreMapper = pairScoreMapper;
        this.recordMapper = recordMapper;
    }

    public PairLookup load(long pairId) {
        PairScore pair = pairScoreMapper.selectById(pairId);
        if (pair == null) {
            return new PairLookup(null, null, null);
        }
        Record lost = recordMapper.selectById(pair.getLostId());
        Record found = recordMapper.selectById(pair.getFoundId());
        return new PairLookup(pair, lost, found);
    }
}
