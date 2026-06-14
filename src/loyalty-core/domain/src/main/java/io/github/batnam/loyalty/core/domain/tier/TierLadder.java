package io.github.batnam.loyalty.core.domain.tier;

import java.util.List;
import java.util.Optional;

/**
 * The ordered Tier Ladder for a Program (CONTEXT.md "Tier Ladder", "Tier"), as a pure value the
 * Member aggregate uses to recompute its current Tier when the Qualifying Balance moves. The
 * {@code :app} layer builds this from the seeded Program/Tier config and passes it in — the domain
 * never reads persistence.
 */
public final class TierLadder {

    /** One rung of the ladder. */
    public record TierRung(String tierCode, int ordinal, long qualifyingThreshold) {
    }

    private final List<TierRung> rungs;

    private TierLadder(List<TierRung> rungs) {
        // copy + sort ascending by ordinal so "highest reached" is the last match
        this.rungs = rungs.stream()
                .sorted((a, b) -> Integer.compare(a.ordinal(), b.ordinal()))
                .toList();
    }

    public static TierLadder of(List<TierRung> rungs) {
        return new TierLadder(List.copyOf(rungs));
    }

    public static TierLadder empty() {
        return new TierLadder(List.of());
    }

    /** Highest Tier whose threshold the qualifying balance meets, or empty if below the lowest rung. */
    public Optional<String> tierFor(long qualifyingBalance) {
        String match = null;
        for (TierRung rung : rungs) {
            if (qualifyingBalance >= rung.qualifyingThreshold()) {
                match = rung.tierCode();
            }
        }
        return Optional.ofNullable(match);
    }
}
