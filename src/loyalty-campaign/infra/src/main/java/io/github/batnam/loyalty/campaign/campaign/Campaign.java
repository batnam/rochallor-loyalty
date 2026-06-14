package io.github.batnam.loyalty.campaign.campaign;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * A per-Program marketing Campaign (L3 §5 {@code campaign}). DRAFT → SCHEDULED → LIVE → ENDED → ARCHIVED;
 * transitions go through {@link CampaignStatus#canTransitionTo} so an illegal move throws rather than
 * corrupting the lifecycle. {@code multiplierRule} is <em>exposed</em> to loyalty-earning (which evaluates
 * it); campaign never interprets it. The LIVE-transition approval gate lives in the service.
 */
@Entity
@Table(name = "campaign")
public class Campaign {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "campaign_id")
    private Long campaignId;

    @Column(name = "program_id", nullable = false, updatable = false)
    private Long programId;

    @Column(name = "program_code", nullable = false, updatable = false)
    private String programCode;

    @Column(name = "name", nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private CampaignStatus status = CampaignStatus.DRAFT;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "ends_at", nullable = false)
    private Instant endsAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "multiplier_rule")
    private String multiplierRule;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "target_segment")
    private String targetSegment;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected Campaign() {
    }

    public static Campaign draft(Long programId, String programCode, String name, Instant startsAt,
                                 Instant endsAt, String multiplierRule, String targetSegment) {
        Campaign c = new Campaign();
        c.programId = programId;
        c.programCode = programCode;
        c.name = name;
        c.startsAt = startsAt;
        c.endsAt = endsAt;
        c.multiplierRule = multiplierRule;
        c.targetSegment = targetSegment;
        c.status = CampaignStatus.DRAFT;
        return c;
    }

    /** Move to {@code target}, rejecting an illegal transition. */
    public void transitionTo(CampaignStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new IllegalStateException("illegal campaign transition " + status + " -> " + target
                    + " (campaign " + campaignId + ")");
        }
        this.status = target;
    }

    /** True when the Campaign carries an earning multiplier — its LIVE transition is approval-gated. */
    public boolean isEconomic() {
        return multiplierRule != null && !multiplierRule.isBlank() && !"{}".equals(multiplierRule.trim());
    }

    @PreUpdate
    void touch() { this.updatedAt = Instant.now(); }

    public Long getCampaignId() { return campaignId; }
    public Long getProgramId() { return programId; }
    public String getProgramCode() { return programCode; }
    public String getName() { return name; }
    public CampaignStatus getStatus() { return status; }
    public Instant getStartsAt() { return startsAt; }
    public Instant getEndsAt() { return endsAt; }
    public String getMultiplierRule() { return multiplierRule; }
    public String getTargetSegment() { return targetSegment; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
