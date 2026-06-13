package io.github.batnam.loyalty.core.cohort;

import io.github.batnam.loyalty.core.ledger.PointLedgerEntry;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Single writer of {@code point_cohort}. Opens a cohort per {@code Earned} entry and consumes points
 * FIFO (oldest unexpired cohort first) on redemption (CONTEXT.md "Expiry", "Point Cohort").
 */
@Component
public class CohortProjection {

    private final CohortRepository cohorts;

    public CohortProjection(CohortRepository cohorts) {
        this.cohorts = cohorts;
    }

    /** Open a new cohort for an {@code Earned} entry whose points are spendable. */
    public void open(PointLedgerEntry earned, Instant expiresAt) {
        Instant earnedAt = earned.getOccurredAt() != null ? earned.getOccurredAt() : Instant.now();
        cohorts.save(PointCohort.open(
                earned.getEntryId(), earned.getMemberId(), earned.getProgramId(),
                earned.getRedeemableDelta(), earnedAt, expiresAt));
    }

    /**
     * Consume {@code amount} points from the Member's oldest unexpired cohorts (FIFO). Returns the
     * cohorts touched. If cohorts are exhausted before {@code amount} is met (e.g. negative balance
     * from a prior clawback), the remainder is simply not tracked against a cohort — the Ledger
     * remains the source of truth and balance can go negative (CONTEXT.md "Negative Redeemable Balance").
     */
    public List<PointCohort> consumeFifo(Long memberId, Long programId, long amount) {
        List<PointCohort> touched = new ArrayList<>();
        long remaining = amount;
        Instant now = Instant.now();
        for (PointCohort c : cohorts.findByMemberIdAndProgramIdOrderByEarnedAtAsc(memberId, programId)) {
            if (remaining <= 0) break;
            if (c.getExpiresAt().isBefore(now)) continue;   // expired cohorts are not consumable
            long take = Math.min(remaining, c.remaining());
            if (take <= 0) continue;
            c.consume(take);
            cohorts.save(c);
            touched.add(c);
            remaining -= take;
        }
        return touched;
    }
}
