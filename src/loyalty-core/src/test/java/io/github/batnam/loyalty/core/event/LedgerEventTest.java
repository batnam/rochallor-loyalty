package io.github.batnam.loyalty.core.event;

import io.github.batnam.loyalty.core.ledger.EntryType;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for {@link LedgerEvent#of}: {@code eventId} is derived from the immutable ledger entry id
 * so it is stable across at-least-once outbox replays (downstream dedups by {@code eventId}).
 */
class LedgerEventTest {

    @Test
    void eventIdIsDerivedFromEntryIdAndStableAcrossReplays() {
        Instant at = Instant.EPOCH.plusSeconds(123);
        LedgerEvent first = LedgerEvent.of("PointsEarned", 10L, 1L, 777L,
                EntryType.Earned, 500, 500, "earn-abc", at);
        LedgerEvent replay = LedgerEvent.of("PointsEarned", 10L, 1L, 777L,
                EntryType.Earned, 500, 500, "earn-abc", at);

        assertThat(first.eventId()).isEqualTo("ledger-777");
        assertThat(replay.eventId()).isEqualTo(first.eventId());   // same entry → same id
        assertThat(first.schemaVersion()).isEqualTo(1);
        assertThat(first.eventType()).isEqualTo("PointsEarned");
        assertThat(first.ledgerEntryType()).isEqualTo(EntryType.Earned);
        assertThat(first.sourceRef()).isEqualTo("earn-abc");
    }
}
