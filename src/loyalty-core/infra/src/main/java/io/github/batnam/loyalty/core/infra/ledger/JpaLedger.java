package io.github.batnam.loyalty.core.infra.ledger;

import io.github.batnam.loyalty.core.domain.ledger.LedgerEntryView;
import io.github.batnam.loyalty.core.domain.port.Ledger;
import io.github.batnam.loyalty.core.ledger.LedgerRepository;
import io.github.batnam.loyalty.core.ledger.PointLedgerEntry;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * JPA-backed adapter for the {@link Ledger} port. Delegates the idempotency probe to the
 * Spring Data repository and maps the {@code @Entity} to a pure {@link LedgerEntryView}.
 */
@Component
public class JpaLedger implements Ledger {

    private final LedgerRepository ledger;

    public JpaLedger(LedgerRepository ledger) {
        this.ledger = ledger;
    }

    @Override
    public Optional<LedgerEntryView> findExisting(String sourceRef,
                                                  io.github.batnam.loyalty.core.domain.ledger.EntryType entryType) {
        io.github.batnam.loyalty.core.ledger.EntryType jpaType =
                io.github.batnam.loyalty.core.ledger.EntryType.valueOf(entryType.name());
        return ledger.findBySourceRefAndEntryType(sourceRef, jpaType).map(JpaLedger::toView);
    }

    @Override
    public List<LedgerEntryView> findEarnedBySourceEvent(String originalEventId) {
        return ledger.findEarnedBySourceEvent(originalEventId, originalEventId + ":%").stream()
                .map(JpaLedger::toView)
                .toList();
    }

    private static LedgerEntryView toView(PointLedgerEntry e) {
        return new LedgerEntryView(
                e.getEntryId(),
                e.getMemberId(),
                e.getProgramId(),
                io.github.batnam.loyalty.core.domain.ledger.EntryType.valueOf(e.getEntryType().name()),
                e.getQualifyingDelta(),
                e.getRedeemableDelta(),
                e.getSourceRef(),
                e.getCreatedAt());
    }
}
