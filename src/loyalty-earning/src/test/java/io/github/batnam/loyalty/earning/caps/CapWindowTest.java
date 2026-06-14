package io.github.batnam.loyalty.earning.caps;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link CapWindow} key derivation — the per-window bucket key that scopes a
 * {@code cap_counter} row (DAY/MONTH/LIFE). Pure; deterministic in UTC. Two events in the same UTC
 * day/month must share a key so their points accumulate against one counter.
 */
class CapWindowTest {

    private static final Instant T = Instant.parse("2026-05-30T10:15:00Z");
    private static final Instant SAME_DAY = Instant.parse("2026-05-30T23:59:59Z");
    private static final Instant NEXT_DAY = Instant.parse("2026-05-31T00:00:01Z");

    @Test
    void dayKeyIsPerUtcCalendarDay() {
        assertThat(CapWindow.DAY.keyFor(T)).isEqualTo("DAY:2026-05-30");
        assertThat(CapWindow.DAY.keyFor(SAME_DAY)).isEqualTo(CapWindow.DAY.keyFor(T));
        assertThat(CapWindow.DAY.keyFor(NEXT_DAY)).isNotEqualTo(CapWindow.DAY.keyFor(T));
    }

    @Test
    void monthKeyIsPerUtcCalendarMonth() {
        assertThat(CapWindow.MONTH.keyFor(T)).isEqualTo("MONTH:2026-05");
        assertThat(CapWindow.MONTH.keyFor(NEXT_DAY)).isEqualTo(CapWindow.MONTH.keyFor(T));   // still May
    }

    @Test
    void lifeKeyIsConstant() {
        assertThat(CapWindow.LIFE.keyFor(T)).isEqualTo("LIFE");
        assertThat(CapWindow.LIFE.keyFor(NEXT_DAY)).isEqualTo(CapWindow.LIFE.keyFor(T));
    }
}
