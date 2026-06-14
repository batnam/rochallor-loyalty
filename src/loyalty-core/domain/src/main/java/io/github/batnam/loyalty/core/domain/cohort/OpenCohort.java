package io.github.batnam.loyalty.core.domain.cohort;

import java.time.Instant;

/**
 * An existing Point Cohort loaded into the Member aggregate for FIFO consumption (CONTEXT.md
 * "Point Cohort", "Expiry"). Mutable within one unit of work: {@link #consume(long)} reduces its
 * remaining points and tracks the delta written <i>this transaction</i> ({@link #consumedThisTx()})
 * so the adapter persists only the change. Pure — no JPA.
 */
public final class OpenCohort {

    private final long cohortId;
    private final long originalAmount;
    private long consumedAmount;
    private final long expiredAmount;
    private final Instant earnedAt;
    private final Instant expiresAt;
    private long consumedThisTx;

    public OpenCohort(long cohortId, long originalAmount, long consumedAmount, long expiredAmount,
                      Instant earnedAt, Instant expiresAt) {
        this.cohortId = cohortId;
        this.originalAmount = originalAmount;
        this.consumedAmount = consumedAmount;
        this.expiredAmount = expiredAmount;
        this.earnedAt = earnedAt;
        this.expiresAt = expiresAt;
    }

    /** Points still available to consume (not yet consumed or expired). */
    public long remaining() {
        return originalAmount - consumedAmount - expiredAmount;
    }

    /** Consume {@code amount} points; called only by the Member aggregate during FIFO redemption. */
    public void consume(long amount) {
        this.consumedAmount += amount;
        this.consumedThisTx += amount;
    }

    public long cohortId() { return cohortId; }
    public Instant expiresAt() { return expiresAt; }
    public Instant earnedAt() { return earnedAt; }

    /** Points consumed from this cohort during the current unit of work (the delta the adapter persists). */
    public long consumedThisTx() { return consumedThisTx; }
}
