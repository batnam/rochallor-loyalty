package io.github.batnam.loyalty.earning.engine;

import java.util.List;

/**
 * Outcome of processing one EarnEvent: whether it was a replay (short-circuited), and the Ledger
 * entries written + summed deltas across all matching rules.
 */
public record EarnResult(
        boolean replayed,
        List<Long> entryIds,
        long totalQualifyingDelta,
        long totalRedeemableDelta
) {
    public static EarnResult replay() {
        return new EarnResult(true, List.of(), 0, 0);
    }
}
