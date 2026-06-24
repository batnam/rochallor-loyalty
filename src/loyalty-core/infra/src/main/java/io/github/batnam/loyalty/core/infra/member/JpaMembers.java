package io.github.batnam.loyalty.core.infra.member;

import io.github.batnam.loyalty.core.cohort.CohortRepository;
import io.github.batnam.loyalty.core.cohort.PointCohort;
import io.github.batnam.loyalty.core.config.CoreProperties;
import io.github.batnam.loyalty.core.domain.cohort.NewCohort;
import io.github.batnam.loyalty.core.domain.cohort.OpenCohort;
import io.github.batnam.loyalty.core.domain.event.DomainEvent;
import io.github.batnam.loyalty.core.domain.ledger.NewLedgerEntry;
import io.github.batnam.loyalty.core.domain.member.Member;
import io.github.batnam.loyalty.core.domain.port.Members;
import io.github.batnam.loyalty.core.event.LedgerEvent;
import io.github.batnam.loyalty.core.ledger.EntryType;
import io.github.batnam.loyalty.core.ledger.LedgerRepository;
import io.github.batnam.loyalty.core.ledger.PointLedgerEntry;
import io.github.batnam.loyalty.core.member.MemberRepository;
import io.github.batnam.loyalty.core.outbox.OutboxRelay;
import io.github.batnam.loyalty.core.program.TierAuthority;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * JPA-backed adapter for the {@link Members} port. Bridges the pure domain aggregate to
 * the {@code @Entity} persistence model + repositories + outbox.
 *
 * <p><b>Lock lifecycle.</b> {@link #loadForUpdate} takes the pessimistic row lock; {@link #save}
 * re-reads the same id <i>inside the same transaction</i>, which Hibernate serves from the
 * persistence-context first-level cache — the already-locked managed instance, not a fresh DB read or
 * a new lock. So the read-modify-write stays serialised (invariant P5). The application service is
 * responsible for wrapping both calls in one {@code @Transactional}.
 */
@Component
public class JpaMembers implements Members {

    private final MemberRepository members;
    private final LedgerRepository ledger;
    private final CohortRepository cohorts;
    private final OutboxRelay outbox;
    private final TierAuthority tierAuthority;
    private final String ledgerTopic;

    public JpaMembers(MemberRepository members, LedgerRepository ledger, CohortRepository cohorts,
                      OutboxRelay outbox, TierAuthority tierAuthority, CoreProperties props) {
        this.members = members;
        this.ledger = ledger;
        this.cohorts = cohorts;
        this.outbox = outbox;
        this.tierAuthority = tierAuthority;
        this.ledgerTopic = props.topics().ledgerEvents();
    }

    @Override
    public Member loadForUpdate(long memberId) {
        // Plain IllegalArgumentException (mapped to 400 by the app's ProblemAdvice) keeps the
        // HTTP-aware CoreException — and thus spring-web — off the :infra classpath.
        io.github.batnam.loyalty.core.member.Member entity = members.findByIdForUpdate(memberId)
                .orElseThrow(() -> new IllegalArgumentException("unknown memberId=" + memberId));
        // Load the Member's cohorts (oldest first) so FIFO redemption is pure domain logic.
        List<OpenCohort> open = cohorts
                .findByMemberIdAndProgramIdOrderByEarnedAtAsc(entity.getMemberId(), entity.getProgramId())
                .stream()
                .map(c -> new OpenCohort(c.getCohortId(), c.getOriginalAmount(), c.getConsumedAmount(),
                        c.getExpiredAmount(), c.getEarnedAt(), c.getExpiresAt()))
                .toList();
        return MemberMapper.toDomain(entity, open);
    }

    @Override
    public void save(Member aggregate) {
        // Same-transaction re-read: returns the managed, already-locked instance from the L1 cache.
        io.github.batnam.loyalty.core.member.Member entity = members.findById(aggregate.memberId())
                .orElseThrow(() -> new IllegalStateException(
                        "member vanished mid-transaction memberId=" + aggregate.memberId()));

        List<NewLedgerEntry> entries = aggregate.pendingEntries();
        List<DomainEvent> events = aggregate.recordedEvents();
        Deque<NewCohort> openCohorts = new ArrayDeque<>(aggregate.pendingCohorts());

        for (int i = 0; i < entries.size(); i++) {
            NewLedgerEntry e = entries.get(i);
            EntryType type = EntryType.valueOf(e.type().name());

            PointLedgerEntry saved = ledger.save(PointLedgerEntry.of(
                    aggregate.memberId(), aggregate.programId(), type,
                    e.qualifyingDelta(), e.redeemableDelta(), e.sourceRef(), e.reason(),
                    e.earnSourceCode(), e.currency(), e.approvalRequestId(), e.occurredAt()));

            // Balance projection — reuse the entity's single-writer mutator (invariant P5).
            entity.applyBalanceDelta(e.redeemableDelta(), e.qualifyingDelta());

            if (e.redeemableDelta() > 0 && !openCohorts.isEmpty()) {
                NewCohort c = openCohorts.poll();
                cohorts.save(PointCohort.open(saved.getEntryId(), aggregate.memberId(),
                        aggregate.programId(), c.originalAmount(), c.earnedAt(), c.expiresAt()));
            }

            // Drain the recorded event to the outbox generically, stamping the persisted entryId.
            if (i < events.size()) {
                DomainEvent ev = events.get(i);
                outbox.enqueue("ledger", "loyalty.ledger." + ev.eventName(), ledgerTopic,
                        String.valueOf(aggregate.memberId()),
                        LedgerEvent.of(ev.eventName(), aggregate.memberId(), aggregate.programId(),
                                saved.getEntryId(), type, ev.qualifyingDelta(), ev.redeemableDelta(),
                                ev.sourceRef(), ev.occurredAt()));
            }
        }

        // Persist FIFO cohort consumption (the delta consumed this transaction). findById returns the
        // same managed instance loaded under the member lock, so .consume() just dirties it for flush.
        for (OpenCohort c : aggregate.consumedCohorts()) {
            if (c.consumedThisTx() == 0) continue;
            cohorts.findById(c.cohortId())
                    .orElseThrow(() -> new IllegalStateException("cohort vanished cohortId=" + c.cohortId()))
                    .consume(c.consumedThisTx());
        }

        // Tier projection — single writer of current_tier_code. The windowed Qualifying Balance is
        // authoritative (CONTEXT.md "Qualifying Metric"): the SUM query issued here triggers a
        // Hibernate autoflush, so the entries just ledger.save()d above are included. The aggregate's
        // in-memory tier is left untouched (it serves only the expiry-override lookup at earn time).
        tierAuthority.recompute(entity, Instant.now());
        // The entity is managed; dirty-checking flushes balance/tier/cohorts at commit.
    }
}
