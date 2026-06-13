package io.github.batnam.loyalty.earning.source;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * A per-Program Earning Rule in the constrained JSON DSL (L3 §5 {@code earning_rule}). Configuration
 * data, not a domain aggregate (L3 §4) — a {@code dsl_json} blob plus a status. Created DRAFT;
 * activation is approval-gated; archive is direct. Never deleted (audit).
 */
@Entity
@Table(name = "earning_rule")
public class EarningRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "rule_id")
    private Long ruleId;

    @Column(name = "program_id", nullable = false, updatable = false)
    private Long programId;

    @Column(name = "earn_source_id", nullable = false, updatable = false)
    private Long earnSourceId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "dsl_json", nullable = false)
    private String dslJson;

    @Column(name = "version", nullable = false)
    private int version;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private RuleStatus status = RuleStatus.DRAFT;

    @Column(name = "effective_from")
    private Instant effectiveFrom;

    @Column(name = "effective_to")
    private Instant effectiveTo;

    @Column(name = "campaign_id")
    private Long campaignId;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.EPOCH;

    protected EarningRule() {
    }

    public static EarningRule draft(Long programId, Long earnSourceId, String dslJson,
                                    Instant effectiveFrom, Instant effectiveTo, Long campaignId) {
        EarningRule r = new EarningRule();
        r.programId = programId;
        r.earnSourceId = earnSourceId;
        r.dslJson = dslJson;
        r.version = 1;
        r.status = RuleStatus.DRAFT;
        r.effectiveFrom = effectiveFrom;
        r.effectiveTo = effectiveTo;
        r.campaignId = campaignId;
        r.updatedAt = Instant.now();
        return r;
    }

    public void activate() {
        this.status = RuleStatus.ACTIVE;
        this.updatedAt = Instant.now();
    }

    public void archive() {
        this.status = RuleStatus.ARCHIVED;
        this.updatedAt = Instant.now();
    }

    public Long getRuleId() { return ruleId; }
    public Long getProgramId() { return programId; }
    public Long getEarnSourceId() { return earnSourceId; }
    public String getDslJson() { return dslJson; }
    public int getVersion() { return version; }
    public RuleStatus getStatus() { return status; }
    public Instant getEffectiveFrom() { return effectiveFrom; }
    public Instant getEffectiveTo() { return effectiveTo; }
    public Long getCampaignId() { return campaignId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
