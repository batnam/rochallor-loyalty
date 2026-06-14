package io.github.batnam.loyalty.core.domain.member;

import io.github.batnam.loyalty.core.domain.ledger.EntryType;
import io.github.batnam.loyalty.core.domain.port.Members;
import io.github.batnam.loyalty.core.domain.support.InMemoryMembers;
import io.github.batnam.loyalty.core.domain.tier.TierLadder;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the {@link Members} unit-of-work seam end-to-end through the in-memory adapter, with no
 * database.
 * load → mutate → save round-trips persisted state and drains the new entry/cohort/event.
 */
class MembersPortRoundTripTest {

    private static final Instant T0 = Instant.parse("2026-01-15T10:00:00Z");

    private static TierLadder ladder() {
        return TierLadder.of(List.of(
                new TierLadder.TierRung("BRONZE", 0, 0),
                new TierLadder.TierRung("SILVER", 1, 1_000)));
    }

    @Test
    void loadMutateSaveRoundTripsThroughThePort() {
        InMemoryMembers members = new InMemoryMembers();
        members.seed(1L, 10L, 99L, 0, 0, null, 1);

        // application-service shape: load under lock, mutate the aggregate, save
        var member = members.loadForUpdate(1L);
        member.appendEarn(1_500, 1_500, "src-1", "CARD_SPEND", "SGD", T0, ladder(), 24);
        members.save(member);

        // re-load reflects the persisted balance + recomputed Tier
        Members port = members;
        var reloaded = port.loadForUpdate(1L);
        assertThat(reloaded.redeemableBalance()).isEqualTo(1_500);
        assertThat(reloaded.qualifyingBalance()).isEqualTo(1_500);
        assertThat(reloaded.currentTierCode()).isEqualTo("SILVER");
        assertThat(reloaded.pendingEntries()).isEmpty();   // a fresh load carries no pending work

        // the save drained exactly one entry, cohort, and event
        assertThat(members.drainedEntries()).hasSize(1);
        assertThat(members.drainedEntries().get(0).type()).isEqualTo(EntryType.Earned);
        assertThat(members.drainedCohorts()).hasSize(1);
        assertThat(members.drainedEvents()).hasSize(1);
    }

    @Test
    void successiveEarnsAccumulateBalance() {
        InMemoryMembers members = new InMemoryMembers();
        members.seed(1L, 10L, 99L, 0, 0, null, 1);

        var first = members.loadForUpdate(1L);
        first.appendEarn(600, 600, "src-1", "CARD_SPEND", "SGD", T0, ladder(), 24);
        members.save(first);

        var second = members.loadForUpdate(1L);
        second.appendEarn(600, 600, "src-2", "CARD_SPEND", "SGD", T0, ladder(), 24);
        members.save(second);

        assertThat(members.loadForUpdate(1L).redeemableBalance()).isEqualTo(1_200);
        assertThat(members.loadForUpdate(1L).currentTierCode()).isEqualTo("SILVER");
        assertThat(members.drainedEntries()).hasSize(2);
    }
}
