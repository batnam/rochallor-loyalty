package io.github.batnam.loyalty.bridge.velocity;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class SlidingWindowCounterStoreTest {

    private final SlidingWindowCounterStore store = new SlidingWindowCounterStore();
    private static final Duration WINDOW = Duration.ofDays(30);

    @Test
    void countsEventsWithinWindowPerCustomer() {
        Instant now = Instant.parse("2026-05-29T00:00:00Z");
        assertThat(store.recordAndCount(1L, now, WINDOW, now)).isEqualTo(1);
        assertThat(store.recordAndCount(1L, now, WINDOW, now)).isEqualTo(2);
        assertThat(store.recordAndCount(2L, now, WINDOW, now)).isEqualTo(1); // independent per customer
    }

    @Test
    void evictsEventsOlderThanWindow() {
        Instant t0 = Instant.parse("2026-04-01T00:00:00Z");
        store.recordAndCount(1L, t0, WINDOW, t0);                 // old event

        Instant now = Instant.parse("2026-05-29T00:00:00Z");      // > 30d later
        int count = store.recordAndCount(1L, now, WINDOW, now);

        assertThat(count).isEqualTo(1); // the April event fell outside the 30-day window
    }
}
