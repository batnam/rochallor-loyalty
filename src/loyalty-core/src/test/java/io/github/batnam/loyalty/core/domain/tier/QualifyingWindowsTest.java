package io.github.batnam.loyalty.core.domain.tier;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the pure window-start arithmetic behind a Program's Qualifying Metric (CONTEXT.md
 * "Qualifying Metric"). LIFETIME has no window; ROLLING_12_MONTHS is a 12-month look-back; CALENDAR_YEAR
 * snaps to the start of the current UTC calendar year.
 */
class QualifyingWindowsTest {

    private static final Instant NOW = Instant.parse("2026-06-24T10:30:00Z");

    @Test
    void lifetimeHasNoWindow() {
        assertThat(QualifyingWindows.windowStart("LIFETIME", NOW)).isEmpty();
        assertThat(QualifyingWindows.isWindowed("LIFETIME")).isFalse();
    }

    @Test
    void unknownMetricFallsBackToNoWindow() {
        assertThat(QualifyingWindows.windowStart("WHATEVER", NOW)).isEmpty();
        assertThat(QualifyingWindows.isWindowed("WHATEVER")).isFalse();
    }

    @Test
    void rolling12IsTwelveMonthsBack() {
        assertThat(QualifyingWindows.windowStart("ROLLING_12_MONTHS", NOW))
                .contains(Instant.parse("2025-06-24T10:30:00Z"));
        assertThat(QualifyingWindows.isWindowed("ROLLING_12_MONTHS")).isTrue();
    }

    @Test
    void calendarYearSnapsToJan1Utc() {
        assertThat(QualifyingWindows.windowStart("CALENDAR_YEAR", NOW))
                .contains(Instant.parse("2026-01-01T00:00:00Z"));
        assertThat(QualifyingWindows.isWindowed("CALENDAR_YEAR")).isTrue();
    }

    @Test
    void rolling12HandlesMonthLengthAtEndOfMonth() {
        // 31 Mar back 12 months stays 31 Mar (no Feb clamp); sanity that ZonedDateTime math is used.
        Instant mar31 = Instant.parse("2026-03-31T00:00:00Z");
        assertThat(QualifyingWindows.windowStart("ROLLING_12_MONTHS", mar31))
                .contains(Instant.parse("2025-03-31T00:00:00Z"));
    }

    @Test
    void calendarYearStartIsAtMidnightUtc() {
        Instant start = QualifyingWindows.windowStart("CALENDAR_YEAR", NOW).orElseThrow();
        assertThat(start.atZone(ZoneOffset.UTC).getDayOfYear()).isEqualTo(1);
        assertThat(start.atZone(ZoneOffset.UTC).getHour()).isZero();
    }
}
