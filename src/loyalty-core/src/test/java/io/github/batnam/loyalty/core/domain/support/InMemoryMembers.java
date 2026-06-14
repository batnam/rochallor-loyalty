package io.github.batnam.loyalty.core.domain.support;

import io.github.batnam.loyalty.core.domain.cohort.NewCohort;
import io.github.batnam.loyalty.core.domain.event.DomainEvent;
import io.github.batnam.loyalty.core.domain.ledger.NewLedgerEntry;
import io.github.batnam.loyalty.core.domain.member.Member;
import io.github.batnam.loyalty.core.domain.member.MemberStatus;
import io.github.batnam.loyalty.core.domain.port.Members;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The second adapter for the {@link Members} port (the first being {@code JpaMembers}) — an in-memory
 * fake. Its existence is what makes the seam <i>real</i>: domain-logic tests load → mutate
 * → save through this with no Postgres. It captures drained entries/cohorts/events for assertions.
 */
public final class InMemoryMembers implements Members {

    /** Mutable persisted state of one Member row. */
    private static final class State {
        final long programId;
        final long customerId;
        MemberStatus status;
        long redeemable;
        long qualifying;
        String tierCode;
        Integer tcsVersion;

        State(long programId, long customerId, MemberStatus status, long redeemable,
              long qualifying, String tierCode, Integer tcsVersion) {
            this.programId = programId;
            this.customerId = customerId;
            this.status = status;
            this.redeemable = redeemable;
            this.qualifying = qualifying;
            this.tierCode = tierCode;
            this.tcsVersion = tcsVersion;
        }
    }

    private final Map<Long, State> rows = new HashMap<>();
    private final List<NewLedgerEntry> drainedEntries = new ArrayList<>();
    private final List<NewCohort> drainedCohorts = new ArrayList<>();
    private final List<DomainEvent> drainedEvents = new ArrayList<>();

    /** Seed a Member row, as if it had been enrolled and (optionally) had prior balances. */
    public void seed(long memberId, long programId, long customerId, long redeemable,
                     long qualifying, String tierCode, Integer tcsVersion) {
        rows.put(memberId, new State(programId, customerId, MemberStatus.ACTIVE, redeemable,
                qualifying, tierCode, tcsVersion));
    }

    @Override
    public Member loadForUpdate(long memberId) {
        State s = rows.get(memberId);
        if (s == null) {
            throw new IllegalArgumentException("unknown memberId=" + memberId);
        }
        return Member.rehydrate(memberId, s.programId, s.customerId, s.status,
                s.redeemable, s.qualifying, s.tierCode, s.tcsVersion);
    }

    @Override
    public void save(Member m) {
        State s = rows.get(m.memberId());
        s.redeemable = m.redeemableBalance();
        s.qualifying = m.qualifyingBalance();
        s.tierCode = m.currentTierCode();
        drainedEntries.addAll(m.pendingEntries());
        drainedCohorts.addAll(m.pendingCohorts());
        drainedEvents.addAll(m.recordedEvents());
    }

    public List<NewLedgerEntry> drainedEntries() { return List.copyOf(drainedEntries); }
    public List<NewCohort> drainedCohorts() { return List.copyOf(drainedCohorts); }
    public List<DomainEvent> drainedEvents() { return List.copyOf(drainedEvents); }
}
