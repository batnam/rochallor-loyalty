package io.github.batnam.loyalty.redemption.reward;

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
 * A per-Program Reward (L3 §5 {@code reward}). DRAFT -> ACTIVE -> ARCHIVED. {@code pointCost} changes
 * bump {@code rewardRevision} so already-redeemed sagas reference the price they were redeemed against.
 * Approval gating (ACTIVE / pointCost) lives in {@link RewardCatalogue}, not on the entity.
 */
@Entity
@Table(name = "reward")
public class Reward {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "reward_id")
    private Long rewardId;

    @Column(name = "program_id", nullable = false, updatable = false)
    private Long programId;

    @Column(name = "reward_type_code", nullable = false, updatable = false)
    private String rewardTypeCode;

    @Column(name = "reward_revision", nullable = false)
    private int rewardRevision = 1;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "point_cost", nullable = false)
    private long pointCost;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private RewardStatus status = RewardStatus.DRAFT;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "fulfillment_params", nullable = false)
    private String fulfillmentParams = "{}";

    @Column(name = "effective_from")
    private Instant effectiveFrom;

    @Column(name = "effective_to")
    private Instant effectiveTo;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected Reward() {
    }

    public static Reward draft(Long programId, String rewardTypeCode, String name, long pointCost,
                               String fulfillmentParams) {
        Reward r = new Reward();
        r.programId = programId;
        r.rewardTypeCode = rewardTypeCode;
        r.name = name;
        r.pointCost = pointCost;
        r.fulfillmentParams = fulfillmentParams == null ? "{}" : fulfillmentParams;
        r.status = RewardStatus.DRAFT;
        return r;
    }

    public void activate() { this.status = RewardStatus.ACTIVE; }
    public void archive() { this.status = RewardStatus.ARCHIVED; }

    public void changePointCost(long newCost) {
        this.pointCost = newCost;
        this.rewardRevision += 1;
    }

    @PreUpdate
    void touch() { this.updatedAt = Instant.now(); }

    public boolean isActive() { return status == RewardStatus.ACTIVE; }

    public Long getRewardId() { return rewardId; }
    public Long getProgramId() { return programId; }
    public String getRewardTypeCode() { return rewardTypeCode; }
    public int getRewardRevision() { return rewardRevision; }
    public String getName() { return name; }
    public long getPointCost() { return pointCost; }
    public RewardStatus getStatus() { return status; }
    public String getFulfillmentParams() { return fulfillmentParams; }
    public Instant getEffectiveFrom() { return effectiveFrom; }
    public Instant getEffectiveTo() { return effectiveTo; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
