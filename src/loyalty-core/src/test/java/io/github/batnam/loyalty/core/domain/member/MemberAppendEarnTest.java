package io.github.batnam.loyalty.core.domain.member;

import io.github.batnam.loyalty.core.domain.cohort.NewCohort;
import io.github.batnam.loyalty.core.domain.event.PointsEarned;
import io.github.batnam.loyalty.core.domain.ledger.EntryType;
import io.github.batnam.loyalty.core.domain.ledger.NewLedgerEntry;
import io.github.batnam.loyalty.core.domain.tier.TierLadder;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests for the {@link Member} aggregate's {@code appendEarn} posting logic — no Spring, no
 * database. Proves the entry-type effect, Tier recompute against a passed ladder, Cohort expiry
 * snapshot, and the recorded {@link PointsEarned} event.
 */
class MemberAppendEarnTest {

    private static final Instant T0 = Instant.parse("2026-01-15T10:00:00Z");

    private static TierLadder ladder() {
        return TierLadder.of(List.of(
                new TierLadder.TierRung("BRONZE", 0, 0),
                new TierLadder.TierRung("SILVER", 1, 1_000),
                new TierLadder.TierRung("GOLD", 2, 5_000)));
    }

    @Test
    void appendEarnAppliesDeltasRecomputesTierOpensCohortAndRecordsEvent() {
        Member m = Member.rehydrate(1L, 10L, 99L, MemberStatus.ACTIVE, 0, 0, null, 1);

        m.appendEarn(1_200, 1_200, "src-1", "CARD_SPEND", "SGD", T0, ladder(), 24);

        assertThat(m.redeemableBalance()).isEqualTo(1_200);
        assertThat(m.qualifyingBalance()).isEqualTo(1_200);
        assertThat(m.currentTierCode()).isEqualTo("SILVER");   // 1200 crosses the 1000 rung

        assertThat(m.pendingEntries()).hasSize(1);
        NewLedgerEntry entry = m.pendingEntries().get(0);
        assertThat(entry.type()).isEqualTo(EntryType.Earned);
        assertThat(entry.qualifyingDelta()).isEqualTo(1_200);
        assertThat(entry.redeemableDelta()).isEqualTo(1_200);
        assertThat(entry.sourceRef()).isEqualTo("src-1");
        assertThat(entry.earnSourceCode()).isEqualTo("CARD_SPEND");

        assertThat(m.pendingCohorts()).hasSize(1);
        NewCohort cohort = m.pendingCohorts().get(0);
        assertThat(cohort.originalAmount()).isEqualTo(1_200);
        assertThat(cohort.earnedAt()).isEqualTo(T0);
        assertThat(cohort.expiresAt())
                .isEqualTo(T0.atZone(ZoneOffset.UTC).plusMonths(24).toInstant());

        assertThat(m.recordedEvents()).hasSize(1);
        assertThat(m.recordedEvents().get(0)).isInstanceOf(PointsEarned.class);
        PointsEarned event = (PointsEarned) m.recordedEvents().get(0);
        assertThat(event.memberId()).isEqualTo(1L);
        assertThat(event.redeemableDelta()).isEqualTo(1_200);
    }

    @Test
    void redeemableOnlyEarnDoesNotMoveTierButStillOpensCohort() {
        Member m = Member.rehydrate(1L, 10L, 99L, MemberStatus.ACTIVE, 5_000, 6_000, "GOLD", 1);

        // qualifyingDelta == 0 → Tier must not be recomputed (CONTEXT.md: redemptions/non-qualifying
        // movements never reduce the Qualifying Balance or the Tier).
        m.appendEarn(0, 300, "src-2", "PROMO", "SGD", T0, ladder(), 24);

        assertThat(m.redeemableBalance()).isEqualTo(5_300);
        assertThat(m.qualifyingBalance()).isEqualTo(6_000);
        assertThat(m.currentTierCode()).isEqualTo("GOLD");        // unchanged
        assertThat(m.pendingCohorts()).hasSize(1);                // r>0 still opens a cohort
    }
}
