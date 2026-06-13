package io.github.batnam.loyalty.campaign.select;

import io.github.batnam.loyalty.campaign.select.WinnerSelection.Entry;
import io.github.batnam.loyalty.campaign.select.WinnerSelection.Winner;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Winner Selection algorithm (L3 §3.3) — the auditable heart of the service. Pure, deterministic,
 * replayable: {@code SEEDED_RNG}/{@code WEIGHTED} derive a seed from {@code (drawingId, drawAt, HMAC
 * secret)} so an auditor with the secret + the immutable entry order (+ frozen weights) reproduces the
 * exact winners; {@code FIRST_N} is arrival-deterministic with no seed. K = min(winnersCount, N) winners
 * without replacement, each carrying its index into the entry-id-ordered set. No I/O.
 */
class WinnerSelectionTest {

    private static final Instant DRAW_AT = Instant.parse("2026-06-30T12:00:00Z");
    private static final String SECRET = "test-hmac-secret";

    /** N entries with one member each (memberId = 100+index), all weight 1, in entry-id order. */
    private static List<Entry> entries(int n) {
        return IntStream.range(0, n).mapToObj(i -> new Entry(100L + i, i, 1)).toList();
    }

    @Test
    void seededRngPicksKDistinctWinnersWithSeedHex() {
        List<Winner> winners = WinnerSelection.select(7L, DRAW_AT, SECRET, entries(10),
                SelectionStrategy.SEEDED_RNG, 3);

        assertThat(winners).hasSize(3);
        assertThat(winners).extracting(Winner::winnerIndex).doesNotHaveDuplicates();
        assertThat(winners).extracting(Winner::memberId).doesNotHaveDuplicates();
        assertThat(winners).allSatisfy(w -> assertThat(w.seedHex()).hasSize(64));   // SHA-256 hex
        // Each winnerIndex is a real position into the entry set.
        assertThat(winners).allSatisfy(w -> assertThat(w.winnerIndex()).isBetween(0, 9));
    }

    @Test
    void seededRngIsReplayable() {
        List<Winner> a = WinnerSelection.select(7L, DRAW_AT, SECRET, entries(10), SelectionStrategy.SEEDED_RNG, 3);
        List<Winner> b = WinnerSelection.select(7L, DRAW_AT, SECRET, entries(10), SelectionStrategy.SEEDED_RNG, 3);
        assertThat(a).isEqualTo(b);
    }

    @Test
    void differentSecretChangesTheSeed() {
        String seedA = WinnerSelection.select(7L, DRAW_AT, "secret-A", entries(10), SelectionStrategy.SEEDED_RNG, 1)
                .get(0).seedHex();
        String seedB = WinnerSelection.select(7L, DRAW_AT, "secret-B", entries(10), SelectionStrategy.SEEDED_RNG, 1)
                .get(0).seedHex();
        assertThat(seedA).isNotEqualTo(seedB);
    }

    @Test
    void differentDrawingIdChangesTheSeed() {
        String s7 = WinnerSelection.select(7L, DRAW_AT, SECRET, entries(10), SelectionStrategy.SEEDED_RNG, 1)
                .get(0).seedHex();
        String s8 = WinnerSelection.select(8L, DRAW_AT, SECRET, entries(10), SelectionStrategy.SEEDED_RNG, 1)
                .get(0).seedHex();
        assertThat(s7).isNotEqualTo(s8);
    }

    @Test
    void firstNPicksTheFirstKByArrivalWithNoSeed() {
        List<Winner> winners = WinnerSelection.select(7L, DRAW_AT, SECRET, entries(10),
                SelectionStrategy.FIRST_N, 3);

        assertThat(winners).hasSize(3);
        assertThat(winners).extracting(Winner::winnerIndex).containsExactly(0, 1, 2);
        assertThat(winners).extracting(Winner::memberId).containsExactly(100L, 101L, 102L);
        assertThat(winners).allSatisfy(w -> assertThat(w.seedHex()).isNull());
    }

    @Test
    void weightedHeavilyFavoursTheHeavyEntry() {
        // One entry with overwhelming weight; K=1 should land on it for essentially any seed.
        List<Entry> weighted = List.of(
                new Entry(200L, 0, 1),
                new Entry(201L, 1, 10_000_000),   // the heavy favourite
                new Entry(202L, 2, 1));
        List<Winner> winners = WinnerSelection.select(7L, DRAW_AT, SECRET, weighted, SelectionStrategy.WEIGHTED, 1);

        assertThat(winners).hasSize(1);
        assertThat(winners.get(0).memberId()).isEqualTo(201L);
        assertThat(winners.get(0).winnerIndex()).isEqualTo(1);
        assertThat(winners.get(0).seedHex()).hasSize(64);
    }

    @Test
    void weightedIsReplayableAndWithoutReplacement() {
        List<Entry> weighted = IntStream.range(0, 6).mapToObj(i -> new Entry(300L + i, i, i + 1)).toList();
        List<Winner> a = WinnerSelection.select(9L, DRAW_AT, SECRET, weighted, SelectionStrategy.WEIGHTED, 4);
        List<Winner> b = WinnerSelection.select(9L, DRAW_AT, SECRET, weighted, SelectionStrategy.WEIGHTED, 4);

        assertThat(a).hasSize(4).isEqualTo(b);
        assertThat(a).extracting(Winner::winnerIndex).doesNotHaveDuplicates();
        assertThat(a).extracting(Winner::memberId).doesNotHaveDuplicates();
    }

    @Test
    void kIsCappedAtTheNumberOfEntries() {
        List<Winner> winners = WinnerSelection.select(7L, DRAW_AT, SECRET, entries(2),
                SelectionStrategy.SEEDED_RNG, 5);
        assertThat(winners).hasSize(2);   // min(5, 2)
    }

    @Test
    void zeroEntriesYieldsNoWinners() {
        assertThat(WinnerSelection.select(7L, DRAW_AT, SECRET, entries(0), SelectionStrategy.SEEDED_RNG, 3))
                .isEmpty();
    }
}
