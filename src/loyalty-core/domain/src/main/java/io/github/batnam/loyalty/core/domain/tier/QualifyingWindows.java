package io.github.batnam.loyalty.core.domain.tier;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

/**
 * Pure window-start arithmetic for a Program's Qualifying Metric (CONTEXT.md "Qualifying Metric").
 * The Qualifying Balance that drives the Tier is {@code SUM(qualifyingDelta)} over the Ledger from
 * this start (inclusive); {@code LIFETIME} has no window (the cumulative balance is authoritative).
 * No persistence here — the {@code :infra} adapter feeds the start into a Ledger SUM query.
 */
public final class QualifyingWindows {

    public static final String LIFETIME = "LIFETIME";
    public static final String ROLLING_12_MONTHS = "ROLLING_12_MONTHS";
    public static final String CALENDAR_YEAR = "CALENDAR_YEAR";

    private QualifyingWindows() {
    }

    /** True when the metric needs a Ledger window (i.e. not {@code LIFETIME}). */
    public static boolean isWindowed(String qualifyingMetric) {
        return windowStart(qualifyingMetric, Instant.EPOCH).isPresent();
    }

    /**
     * Inclusive lower bound of the window at {@code now}: {@code LIFETIME} → empty;
     * {@code ROLLING_12_MONTHS} → now minus 12 months; {@code CALENDAR_YEAR} → start of the current
     * UTC calendar year. All arithmetic in UTC.
     */
    public static Optional<Instant> windowStart(String qualifyingMetric, Instant now) {
        return switch (qualifyingMetric) {
            case ROLLING_12_MONTHS -> Optional.of(now.atZone(ZoneOffset.UTC).minusMonths(12).toInstant());
            case CALENDAR_YEAR -> Optional.of(now.atZone(ZoneOffset.UTC).toLocalDate()
                    .withDayOfYear(1).atStartOfDay(ZoneOffset.UTC).toInstant());
            default -> Optional.empty();   // LIFETIME (and any unknown metric) → cumulative balance
        };
    }
}
