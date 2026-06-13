package io.github.batnam.loyalty.campaign.select;

/**
 * How a Drawing picks its winners (L3 §3.3):
 * <ul>
 *   <li>{@link #SEEDED_RNG} — uniform draw without replacement from a deterministic seed; replayable.</li>
 *   <li>{@link #WEIGHTED} — weighted draw without replacement using the per-entry weights frozen at entry
 *       time; replayable from the same seed.</li>
 *   <li>{@link #FIRST_N} — the first K entries by arrival ({@code entry_id} order); no seed, no RNG.</li>
 * </ul>
 */
public enum SelectionStrategy {
    SEEDED_RNG,
    WEIGHTED,
    FIRST_N
}
