package io.github.batnam.loyalty.core.reversal;

import io.github.batnam.loyalty.core.domain.ledger.LedgerEntryView;
import io.github.batnam.loyalty.core.domain.port.Ledger;
import io.github.batnam.loyalty.core.ledger.LedgerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Application service for the payment-reversal clawback. A {@code loyalty.payment.reversed.v1} event
 * carries the original earn event's id; this finds every {@code Earned} entry that event produced
 * (one payment may fire several rules) and posts a matching {@code Reversed} entry per Member, reusing
 * the original entry's {@code sourceRef} so the {@code (sourceRef, Reversed)} idempotency holds.
 *
 * <p>Reversal of an event that never earned (no matching entries) is a silent no-op.
 */
@Service
public class ReversalService {

    private static final Logger log = LoggerFactory.getLogger(ReversalService.class);

    private final Ledger ledgerPort;
    private final LedgerService ledgerService;

    public ReversalService(Ledger ledgerPort, LedgerService ledgerService) {
        this.ledgerPort = ledgerPort;
        this.ledgerService = ledgerService;
    }

    public void reversePaymentEvent(String originalEventId) {
        List<LedgerEntryView> earned = ledgerPort.findEarnedBySourceEvent(originalEventId);
        if (earned.isEmpty()) {
            log.warn("reversal for originalEventId={} matched no Earned entries — no-op", originalEventId);
            return;
        }
        for (LedgerEntryView e : earned) {
            ledgerService.appendReversed(e.memberId(), e.programId(),
                    e.qualifyingDelta(), e.redeemableDelta(), e.sourceRef());
        }
        log.debug("reversed {} Earned entrie(s) for originalEventId={}", earned.size(), originalEventId);
    }
}
