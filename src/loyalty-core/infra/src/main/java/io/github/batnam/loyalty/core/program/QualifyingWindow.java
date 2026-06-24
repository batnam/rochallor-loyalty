package io.github.batnam.loyalty.core.program;

import io.github.batnam.loyalty.core.domain.tier.QualifyingWindows;
import io.github.batnam.loyalty.core.ledger.LedgerRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;

/**
 * Computes a Member's <i>windowed</i> Qualifying Balance for a Program's Qualifying Metric
 * (CONTEXT.md "Qualifying Metric"). Lives in {@code :infra} because it bridges the pure window-start
 * arithmetic ({@link QualifyingWindows}) to a Ledger SUM query — both visible here, neither reachable
 * from {@code :domain}. For {@code LIFETIME} it returns the denormalized cumulative balance unchanged
 * (no query); for the windowed metrics it sums {@code qualifyingDelta} from the window start.
 */
@Component
public class QualifyingWindow {

    private final LedgerRepository ledger;

    public QualifyingWindow(LedgerRepository ledger) {
        this.ledger = ledger;
    }

    /** Window lower bound at {@code now} for the metric — empty for {@code LIFETIME}. */
    public Optional<Instant> windowStart(String qualifyingMetric, Instant now) {
        return QualifyingWindows.windowStart(qualifyingMetric, now);
    }

    /**
     * The Qualifying Balance to feed the Tier ladder: the cumulative balance for {@code LIFETIME},
     * else {@code SUM(qualifyingDelta)} over the Ledger window for this member/program at {@code now}.
     */
    public long windowedQualifying(long memberId, long programId, String qualifyingMetric,
                                   long cumulativeQualifyingBalance, Instant now) {
        Optional<Instant> start = windowStart(qualifyingMetric, now);
        if (start.isEmpty()) {
            return cumulativeQualifyingBalance;   // LIFETIME — denormalized cumulative is authoritative
        }
        return ledger.sumQualifyingSince(memberId, programId, start.get());
    }
}
