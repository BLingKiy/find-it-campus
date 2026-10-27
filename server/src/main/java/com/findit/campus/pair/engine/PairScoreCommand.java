package com.findit.campus.pair.engine;

import com.findit.campus.pair.domain.PairSide;

/**
 * 一对（LOST 端 + FOUND 端）的打分输入。
 *
 * <p>只在内存里存在，不落库、不进日志：它持有两端原文，而原文可能含用户没打码的
 * 联系人称呼等内容。需要留痕的地方（{@code t_pair_score}）只存分数与其元数据。
 *
 * <p>{@code promptVersion} 随输入一起传：同一条 pair 在不同 prompt 版本下的分数不可比，
 * 所以版本号必须和分数写在同一行，而不是"从配置文件反查当时用的是哪版"。
 */
public record PairScoreCommand(PairSide lost, PairSide found, String promptVersion) {

    public Long lostId() {
        return lost.recordId();
    }

    public Long foundId() {
        return found.recordId();
    }
}
