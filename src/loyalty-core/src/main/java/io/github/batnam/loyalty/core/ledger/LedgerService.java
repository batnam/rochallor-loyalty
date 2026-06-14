package io.github.batnam.loyalty.core.ledger;

import io.github.batnam.loyalty.core.domain.ledger.LedgerEntryView;
import io.github.batnam.loyalty.core.domain.member.Member;
import io.github.batnam.loyalty.core.domain.port.Ledger;
import io.github.batnam.loyalty.core.domain.port.Members;
import io.github.batnam.loyalty.core.domain.tier.TierLadder;
import io.github.batnam.loyalty.core.program.ProgramConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * The Ledger Service (Write) — the application orchestrator for every Point Ledger append
 * (Earned / Redeemed / Expired / Adjusted). Onion path: each method probes idempotency via
 * the {@code Ledger} port, then loads → mutates → saves the pure {@link Member} aggregate through the
 * {@code Members} port within one transaction. The {@code :infra} adapter owns the row lock, the
 * single-writer balance/cohort/tier flush (invariant P5), Ledger immutability, and the outbox drain —
 * this class holds no persistence concerns of its own.
 *
 * <p>Idempotency: {@code (sourceRef, entryType)} is unique; a replay returns the original entry view
 * as a silent no-op.
 */
@Service
public class LedgerService {

    private static final Logger log = LoggerFactory.getLogger(LedgerService.class);

    private final ProgramConfigService programConfig;
    private final Members memberPort;
    private final Ledger ledgerPort;

    public LedgerService(ProgramConfigService programConfig, Members memberPort, Ledger ledgerPort) {
        this.programConfig = programConfig;
        this.memberPort = memberPort;
        this.ledgerPort = ledgerPort;
    }

    /** Append an {@code Earned} entry for a matched rule fire (called by loyalty-earning). */
    @Transactional
    public AppendResult appendEarn(long memberId, long programId, String sourceRef,
                                   long qualifyingDelta, long redeemableDelta, String earnSourceCode,
                                   String currency, Instant occurredAt) {
        Optional<LedgerEntryView> existing = ledgerPort.findExisting(sourceRef, d(EntryType.Earned));
        if (existing.isPresent()) {
            return new AppendResult(existing.get(), true);
        }
        Instant earnedAt = occurredAt != null ? occurredAt : Instant.now();

        Member member = memberPort.loadForUpdate(memberId);
        int expiryMonths = programConfig.effectiveExpiryMonths(programId, member.currentTierCode());
        member.appendEarn(qualifyingDelta, redeemableDelta, sourceRef, earnSourceCode, currency,
                earnedAt, tierLadder(programId), expiryMonths);
        memberPort.save(member);

        LedgerEntryView saved = requireView(sourceRef, EntryType.Earned);
        log.debug("appended Earned entryId={} memberId={} +{}", saved.entryId(), memberId, redeemableDelta);
        return new AppendResult(saved, false);
    }

    /** Append a {@code Redeemed} entry on reservation commit; the aggregate consumes cohorts FIFO. */
    @Transactional
    public AppendResult appendRedeemed(long memberId, long programId, long points, String sourceRef) {
        Optional<LedgerEntryView> existing = ledgerPort.findExisting(sourceRef, d(EntryType.Redeemed));
        if (existing.isPresent()) {
            return new AppendResult(existing.get(), true);
        }
        Member member = memberPort.loadForUpdate(memberId);
        member.appendRedeemed(points, sourceRef, Instant.now());
        memberPort.save(member);
        return new AppendResult(requireView(sourceRef, EntryType.Redeemed), false);
    }

    /** Append an {@code Expired} entry for an unconsumed expired cohort (called by the Expiry Job). */
    @Transactional
    public AppendResult appendExpired(long memberId, long programId, long amount, String sourceRef) {
        Optional<LedgerEntryView> existing = ledgerPort.findExisting(sourceRef, d(EntryType.Expired));
        if (existing.isPresent()) {
            return new AppendResult(existing.get(), true);
        }
        Member member = memberPort.loadForUpdate(memberId);
        member.appendExpired(amount, sourceRef, Instant.now(), tierLadder(programId));
        memberPort.save(member);
        return new AppendResult(requireView(sourceRef, EntryType.Expired), false);
    }

    /** Append an {@code Adjusted} entry from a confirmed approval request (Maker-Checker via BEP). */
    @Transactional
    public AppendResult appendAdjusted(long memberId, long programId, long qualifyingDelta,
                                       long redeemableDelta, String reason, Long approvalRequestId,
                                       String sourceRef) {
        Optional<LedgerEntryView> existing = ledgerPort.findExisting(sourceRef, d(EntryType.Adjusted));
        if (existing.isPresent()) {
            return new AppendResult(existing.get(), true);
        }
        Member member = memberPort.loadForUpdate(memberId);
        member.appendAdjusted(qualifyingDelta, redeemableDelta, reason, approvalRequestId, sourceRef,
                Instant.now(), tierLadder(programId));
        memberPort.save(member);
        return new AppendResult(requireView(sourceRef, EntryType.Adjusted), false);
    }

    /** Build the pure-domain Tier Ladder for a Program from the seeded Tier config. */
    private TierLadder tierLadder(long programId) {
        return TierLadder.of(programConfig.tierLadder(programId).stream()
                .map(t -> new TierLadder.TierRung(t.getTierCode(), t.getOrdinal(), t.getQualifyingThreshold()))
                .toList());
    }

    /** Read back the just-written (or pre-existing) entry as a pure view via the Ledger port. */
    private LedgerEntryView requireView(String sourceRef, EntryType type) {
        return ledgerPort.findExisting(sourceRef, d(type))
                .orElseThrow(() -> new IllegalStateException(
                        "ledger entry not persisted sourceRef=" + sourceRef + " type=" + type));
    }

    /** App→domain EntryType (the domain port speaks the pure enum). */
    private static io.github.batnam.loyalty.core.domain.ledger.EntryType d(EntryType type) {
        return io.github.batnam.loyalty.core.domain.ledger.EntryType.valueOf(type.name());
    }
}
