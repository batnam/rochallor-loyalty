package io.github.batnam.loyalty.core.domain.cohort;

import java.time.Instant;

/**
 * A Point Cohort the aggregate opened for an {@code Earned} entry in the current transaction
 * (CONTEXT.md "Point Cohort", "Expiry"). The {@code expiresAt} is snapshotted at earn time from the
 * Member's effective expiry months — Program-config changes never retroactively affect it.
 * Not yet persisted, so it carries no {@code entryId}; the adapter links it to the saved entry.
 */
public record NewCohort(
        long originalAmount,
        Instant earnedAt,
        Instant expiresAt
) {
}
