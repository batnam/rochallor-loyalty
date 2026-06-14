package io.github.batnam.loyalty.core.domain.member;

import io.github.batnam.loyalty.core.domain.cohort.NewCohort;
import io.github.batnam.loyalty.core.domain.cohort.OpenCohort;
import io.github.batnam.loyalty.core.domain.event.DomainEvent;
import io.github.batnam.loyalty.core.domain.event.PointsAdjusted;
import io.github.batnam.loyalty.core.domain.event.PointsEarned;
import io.github.batnam.loyalty.core.domain.event.PointsExpired;
import io.github.batnam.loyalty.core.domain.event.PointsRedeemed;
import io.github.batnam.loyalty.core.domain.ledger.EntryType;
import io.github.batnam.loyalty.core.domain.ledger.NewLedgerEntry;
import io.github.batnam.loyalty.core.domain.tier.TierLadder;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * The Member aggregate root of the points-posting boundary (CONTEXT.md "Aggregate Root", "Member").
 * Pure domain — no Spring, no JPA. It holds <i>current state</i> (balance/tier projections) plus the
 * entries, cohorts, and events produced <i>in this transaction</i>; it never loads Ledger history.
 *
 * <p>It is the only producer of new Point Ledger Entries (preserving invariant P5 and Ledger
 * immutability) and it <i>records</i> Domain Events rather than publishing them. An {@code :infra}
 * adapter rehydrates it from a locked persistence row, runs a mutation, then drains
 * {@link #pendingEntries()} / {@link #pendingCohorts()} / {@link #recordedEvents()} into the database
 * and outbox within the same transaction (see the {@code Members} port).
 */
public final class Member {

    private final long memberId;
    private final long programId;
    private final long customerId;
    private MemberStatus status;
    private long redeemableBalance;
    private long qualifyingBalance;
    private String currentTierCode;
    private Integer tcsVersionAccepted;

    // --- open Point Cohorts loaded for FIFO redemption (oldest first) ---
    private final List<OpenCohort> openCohorts;

    // --- produced during the current unit of work, drained by the adapter on save ---
    private final List<NewLedgerEntry> pendingEntries = new ArrayList<>();
    private final List<NewCohort> pendingCohorts = new ArrayList<>();
    private final List<OpenCohort> touchedCohorts = new ArrayList<>();
    private final List<DomainEvent> recordedEvents = new ArrayList<>();

    private Member(long memberId, long programId, long customerId, MemberStatus status,
                   long redeemableBalance, long qualifyingBalance, String currentTierCode,
                   Integer tcsVersionAccepted, List<OpenCohort> openCohorts) {
        this.memberId = memberId;
        this.programId = programId;
        this.customerId = customerId;
        this.status = status;
        this.redeemableBalance = redeemableBalance;
        this.qualifyingBalance = qualifyingBalance;
        this.currentTierCode = currentTierCode;
        this.tcsVersionAccepted = tcsVersionAccepted;
        this.openCohorts = List.copyOf(openCohorts);
    }

    /**
     * Reconstruct the aggregate from its persisted state (no open cohorts loaded — for paths that
     * don't redeem). Called by the {@code Members} adapter after it has locked the persistence row.
     */
    public static Member rehydrate(long memberId, long programId, long customerId, MemberStatus status,
                                   long redeemableBalance, long qualifyingBalance, String currentTierCode,
                                   Integer tcsVersionAccepted) {
        return rehydrate(memberId, programId, customerId, status, redeemableBalance, qualifyingBalance,
                currentTierCode, tcsVersionAccepted, List.of());
    }

    /** Rehydrate with the Member's open Point Cohorts (oldest first) for FIFO redemption. */
    public static Member rehydrate(long memberId, long programId, long customerId, MemberStatus status,
                                   long redeemableBalance, long qualifyingBalance, String currentTierCode,
                                   Integer tcsVersionAccepted, List<OpenCohort> openCohorts) {
        return new Member(memberId, programId, customerId, status, redeemableBalance, qualifyingBalance,
                currentTierCode, tcsVersionAccepted, openCohorts);
    }

    /**
     * Post an {@code Earned} entry for a matched rule fire: apply the deltas, recompute the Tier from
     * the ladder when the Qualifying Balance moves, open a Point Cohort for the spendable points
     * (snapshotting expiry from {@code expiryMonths}), and record a {@link PointsEarned} event.
     *
     * <p>Idempotency on {@code sourceRef} is the caller's gate (it probes the Ledger before loading
     * the aggregate); this method assumes the append is not a replay. Balances may go negative
     * (CONTEXT.md "Negative Redeemable Balance").
     */
    public void appendEarn(long qualifyingDelta, long redeemableDelta, String sourceRef,
                           String earnSourceCode, String currency, Instant occurredAt,
                           TierLadder ladder, int expiryMonths) {
        this.redeemableBalance += redeemableDelta;
        this.qualifyingBalance += qualifyingDelta;
        if (qualifyingDelta != 0) {
            this.currentTierCode = ladder.tierFor(this.qualifyingBalance).orElse(null);
        }

        pendingEntries.add(new NewLedgerEntry(EntryType.Earned, qualifyingDelta, redeemableDelta,
                sourceRef, null, earnSourceCode, currency, null, occurredAt));

        if (redeemableDelta > 0) {
            Instant expiresAt = occurredAt.atZone(ZoneOffset.UTC).plusMonths(expiryMonths).toInstant();
            pendingCohorts.add(new NewCohort(redeemableDelta, occurredAt, expiresAt));
        }

        recordedEvents.add(new PointsEarned(memberId, programId, qualifyingDelta, redeemableDelta,
                sourceRef, occurredAt));
    }

    /**
     * Post an {@code Expired} entry for an unconsumed expired cohort (called by the Expiry Job). Both
     * balances drop by {@code amount}; the Tier is recomputed because the Qualifying Balance moved.
     */
    public void appendExpired(long amount, String sourceRef, Instant occurredAt, TierLadder ladder) {
        this.redeemableBalance -= amount;
        this.qualifyingBalance -= amount;
        this.currentTierCode = ladder.tierFor(this.qualifyingBalance).orElse(null);

        pendingEntries.add(new NewLedgerEntry(EntryType.Expired, -amount, -amount, sourceRef,
                "cohort expiry", null, null, null, occurredAt));
        recordedEvents.add(new PointsExpired(memberId, programId, -amount, -amount, sourceRef, occurredAt));
    }

    /**
     * Post an {@code Adjusted} entry from a confirmed Maker-Checker approval. Recomputes the Tier only
     * when the Qualifying Balance moved. Deltas are signed and configurable per adjustment.
     */
    public void appendAdjusted(long qualifyingDelta, long redeemableDelta, String reason,
                               Long approvalRequestId, String sourceRef, Instant occurredAt,
                               TierLadder ladder) {
        this.redeemableBalance += redeemableDelta;
        this.qualifyingBalance += qualifyingDelta;
        if (qualifyingDelta != 0) {
            this.currentTierCode = ladder.tierFor(this.qualifyingBalance).orElse(null);
        }

        pendingEntries.add(new NewLedgerEntry(EntryType.Adjusted, qualifyingDelta, redeemableDelta,
                sourceRef, reason, null, null, approvalRequestId, occurredAt));
        recordedEvents.add(new PointsAdjusted(memberId, programId, qualifyingDelta, redeemableDelta,
                sourceRef, occurredAt));
    }

    /**
     * Post a {@code Redeemed} entry on reservation commit. Decrements the spendable balance and
     * consumes the Member's oldest unexpired cohorts FIFO (CONTEXT.md "Expiry"); the Qualifying
     * Balance and Tier are untouched. If cohorts are exhausted (e.g. from a prior negative balance),
     * the remainder simply isn't tracked against a cohort — the Ledger stays the source of truth.
     */
    public void appendRedeemed(long points, String sourceRef, Instant now) {
        this.redeemableBalance -= points;

        long remaining = points;
        for (OpenCohort c : openCohorts) {            // oldest-first
            if (remaining <= 0) break;
            if (c.expiresAt().isBefore(now)) continue;    // expired cohorts aren't consumable
            long take = Math.min(remaining, c.remaining());
            if (take <= 0) continue;
            c.consume(take);
            touchedCohorts.add(c);
            remaining -= take;
        }

        pendingEntries.add(new NewLedgerEntry(EntryType.Redeemed, 0, -points,
                sourceRef, null, null, null, null, now));
        recordedEvents.add(new PointsRedeemed(memberId, programId, 0, -points, sourceRef, now));
    }

    public long memberId() { return memberId; }
    public long programId() { return programId; }
    public long customerId() { return customerId; }
    public MemberStatus status() { return status; }
    public long redeemableBalance() { return redeemableBalance; }
    public long qualifyingBalance() { return qualifyingBalance; }
    public String currentTierCode() { return currentTierCode; }
    public Integer tcsVersionAccepted() { return tcsVersionAccepted; }

    public List<NewLedgerEntry> pendingEntries() { return List.copyOf(pendingEntries); }
    public List<NewCohort> pendingCohorts() { return List.copyOf(pendingCohorts); }
    public List<OpenCohort> consumedCohorts() { return List.copyOf(touchedCohorts); }
    public List<DomainEvent> recordedEvents() { return List.copyOf(recordedEvents); }
}
