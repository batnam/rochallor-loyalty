package io.github.batnam.loyalty.core.cohort;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Per-{@code Earned}-entry FIFO consumption tracker (CONTEXT.md "Point Cohort"). A rebuildable
 * projection — losing it does not destroy the Ledger. Tracks how much of one earn cohort has been
 * consumed by redemptions and how much has expired.
 */
@Entity
@Table(name = "point_cohort")
public class PointCohort {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "cohort_id")
    private Long cohortId;

    @Column(name = "entry_id", nullable = false, updatable = false)
    private Long entryId;

    @Column(name = "member_id", nullable = false, updatable = false)
    private Long memberId;

    @Column(name = "program_id", nullable = false, updatable = false)
    private Long programId;

    @Column(name = "original_amount", nullable = false, updatable = false)
    private long originalAmount;

    @Column(name = "consumed_amount", nullable = false)
    private long consumedAmount;

    @Column(name = "expired_amount", nullable = false)
    private long expiredAmount;

    @Column(name = "earned_at", nullable = false, updatable = false)
    private Instant earnedAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    protected PointCohort() {
    }

    public static PointCohort open(Long entryId, Long memberId, Long programId,
                                   long originalAmount, Instant earnedAt, Instant expiresAt) {
        PointCohort c = new PointCohort();
        c.entryId = entryId;
        c.memberId = memberId;
        c.programId = programId;
        c.originalAmount = originalAmount;
        c.consumedAmount = 0;
        c.expiredAmount = 0;
        c.earnedAt = earnedAt;
        c.expiresAt = expiresAt;
        return c;
    }

    /** Points still available to consume (not yet consumed or expired). */
    public long remaining() {
        return originalAmount - consumedAmount - expiredAmount;
    }

    public void consume(long amount) { this.consumedAmount += amount; }

    public void expire(long amount) { this.expiredAmount += amount; }

    public Long getCohortId() { return cohortId; }
    public Long getEntryId() { return entryId; }
    public Long getMemberId() { return memberId; }
    public Long getProgramId() { return programId; }
    public long getOriginalAmount() { return originalAmount; }
    public long getConsumedAmount() { return consumedAmount; }
    public long getExpiredAmount() { return expiredAmount; }
    public Instant getEarnedAt() { return earnedAt; }
    public Instant getExpiresAt() { return expiresAt; }
}
