package com.findit.campus.record.web.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.findit.campus.common.config.JacksonConfig;
import com.findit.campus.common.masking.MaskingPolicy;
import com.findit.campus.record.domain.PairCardFacts;

import com.fasterxml.jackson.annotation.JsonFormat;

/**
 * 详情 / 我的启事内嵌的 pair 卡片（契约 §5.1）。
 *
 * <p><b>本类没有、也不允许有 {@code contact} 字段</b>：号码只从 {@code GET /api/pairs/{id}/contact}
 * 出，卡片只给 {@code contactAvailable}/{@code contactHint} 两个"按钮状态"。这不是靠注释约束，
 * 而是靠类型：{@link PairCardFacts} 里就没有对方号码，DTO 想拼也拼不出来。
 *
 * <p>打码在<b>这个出口</b>做，采用 C1 的 {@link MaskingPolicy} 单点实现：对方
 * {@code title}/{@code location} 与两端附言都过同一套规则。为什么不信任"写库时已经打过码"：
 * 附言确实在写入时打过码，但 {@code title}/{@code location} 是原文，而且以后若有人给卡片加
 * 新文本字段，出口统一打码是最不容易漏的默认。
 *
 * <p>字段口径全部由 {@link PairCardFacts} 提供（三档、可用性、文案都在 pair 上下文算好），
 * 本类只做字段搬运与遮蔽，避免"同一个公式两处实现"。
 */
public class PairCard {

    private Long pairId;
    private BigDecimal score;
    private Integer scorePercent;
    private String tier;
    private String status;
    private Boolean matched;
    private Boolean myConfirmed;
    private Boolean otherConfirmed;

    @JsonFormat(pattern = JacksonConfig.DATE_TIME_PATTERN)
    private LocalDateTime lostConfirmedAt;

    @JsonFormat(pattern = JacksonConfig.DATE_TIME_PATTERN)
    private LocalDateTime foundConfirmedAt;

    private String engine;
    private Boolean degraded;
    private Counterpart counterpart;
    private String mySide;
    private Boolean myUnlocked;
    private Boolean otherUnlocked;
    private String myNote;
    private String otherNote;
    private Boolean contactAvailable;
    private String contactHint;

    public static PairCard of(PairCardFacts facts) {
        PairCard card = new PairCard();
        card.pairId = facts.pairId();
        card.score = facts.score();
        card.scorePercent = facts.scorePercent();
        card.tier = facts.tier();
        card.status = facts.status();
        card.matched = facts.matched();
        card.myConfirmed = facts.myConfirmed();
        card.otherConfirmed = facts.otherConfirmed();
        card.lostConfirmedAt = facts.lostConfirmedAt();
        card.foundConfirmedAt = facts.foundConfirmedAt();
        card.engine = facts.engine();
        card.degraded = facts.degraded();
        card.mySide = facts.mySide();
        card.myUnlocked = facts.myUnlocked();
        card.otherUnlocked = facts.otherUnlocked();
        card.myNote = MaskingPolicy.maskDescription(facts.myNote());
        card.otherNote = MaskingPolicy.maskDescription(facts.otherNote());
        card.contactAvailable = facts.contactAvailable();
        card.contactHint = facts.contactHint();
        card.counterpart = new Counterpart(
                facts.counterpartRecordId(),
                facts.counterpartType(),
                MaskingPolicy.maskDescription(facts.counterpartTitle()),
                MaskingPolicy.maskLocation(facts.counterpartLocation()),
                facts.counterpartOccurredAt(),
                facts.counterpartCreatedAt(),
                facts.counterpartStatus());
        return card;
    }

    /**
     * 对方的可见摘要（契约 §5.1 {@code counterpart}）。
     *
     * <p>{@code status} 让前端知道对端是 CLOSED/COMPLETED：卡片可能出现对端 COMPLETED（保留期内），
     * 此时"已解决"标记比"可能相关"更重要。已删/CLOSED/到期的对端不会进卡片（SQL 读时剔除）。
     */
    public static class Counterpart {

        private Long recordId;
        private String type;
        private String title;
        private String location;

        @JsonFormat(pattern = JacksonConfig.DATE_TIME_PATTERN)
        private LocalDateTime occurredAt;

        @JsonFormat(pattern = JacksonConfig.DATE_TIME_PATTERN)
        private LocalDateTime createdAt;

        private String status;

        Counterpart(Long recordId, String type, String title, String location,
                    LocalDateTime occurredAt, LocalDateTime createdAt, String status) {
            this.recordId = recordId;
            this.type = type;
            this.title = title;
            this.location = location;
            this.occurredAt = occurredAt;
            this.createdAt = createdAt;
            this.status = status;
        }

        public Long getRecordId() {
            return recordId;
        }

        public String getType() {
            return type;
        }

        public String getTitle() {
            return title;
        }

        public String getLocation() {
            return location;
        }

        public LocalDateTime getOccurredAt() {
            return occurredAt;
        }

        public LocalDateTime getCreatedAt() {
            return createdAt;
        }

        public String getStatus() {
            return status;
        }
    }

    public Long getPairId() {
        return pairId;
    }

    public BigDecimal getScore() {
        return score;
    }

    public Integer getScorePercent() {
        return scorePercent;
    }

    public String getTier() {
        return tier;
    }

    public String getStatus() {
        return status;
    }

    public Boolean getMatched() {
        return matched;
    }

    public Boolean getMyConfirmed() {
        return myConfirmed;
    }

    public Boolean getOtherConfirmed() {
        return otherConfirmed;
    }

    public LocalDateTime getLostConfirmedAt() {
        return lostConfirmedAt;
    }

    public LocalDateTime getFoundConfirmedAt() {
        return foundConfirmedAt;
    }

    public String getEngine() {
        return engine;
    }

    public Boolean getDegraded() {
        return degraded;
    }

    public Counterpart getCounterpart() {
        return counterpart;
    }

    public String getMySide() {
        return mySide;
    }

    public Boolean getMyUnlocked() {
        return myUnlocked;
    }

    public Boolean getOtherUnlocked() {
        return otherUnlocked;
    }

    public String getMyNote() {
        return myNote;
    }

    public String getOtherNote() {
        return otherNote;
    }

    public Boolean getContactAvailable() {
        return contactAvailable;
    }

    public String getContactHint() {
        return contactHint;
    }
}
