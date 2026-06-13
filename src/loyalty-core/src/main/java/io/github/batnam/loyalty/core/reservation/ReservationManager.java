package io.github.batnam.loyalty.core.reservation;

import io.github.batnam.loyalty.core.config.CoreProperties;
import io.github.batnam.loyalty.core.domain.member.RedeemableBalance;
import io.github.batnam.loyalty.core.domain.port.Reservations;
import io.github.batnam.loyalty.core.domain.reservation.Reservation;
import io.github.batnam.loyalty.core.error.CoreException;
import io.github.batnam.loyalty.core.event.LedgerEvent;
import io.github.batnam.loyalty.core.ledger.AppendResult;
import io.github.batnam.loyalty.core.ledger.EntryType;
import io.github.batnam.loyalty.core.ledger.LedgerService;
import io.github.batnam.loyalty.core.member.Member;
import io.github.batnam.loyalty.core.member.MemberRepository;
import io.github.batnam.loyalty.core.outbox.OutboxRelay;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Reservation Manager — orchestrates the two-phase redemption flow (CONTEXT.md "Redemption",
 * "Reservation"; L3 §3.2) over the {@code Reservations} port. The lifecycle transitions live on the
 * pure {@link Reservation} aggregate; this service supplies the cross-aggregate side effects (the
 * reserve balance gate, the commit's {@code Redeemed} Ledger entry, the outbox events).
 *
 * <p><b>Balance model</b> (CONTEXT.md "Reservation"): a HELD reservation does <i>not</i> mutate
 * {@code member.redeemable_balance}. The reserve gate checks the <i>Effective</i> balance
 * ({@code redeemable_balance − SUM(active HELD)}) under the Member lock; only {@code commit()} writes
 * the {@code Redeemed} Ledger entry that decrements the balance.
 */
@Service
public class ReservationManager {

    private static final Logger log = LoggerFactory.getLogger(ReservationManager.class);

    private final Reservations reservations;
    private final MemberRepository members;
    private final LedgerService ledger;
    private final OutboxRelay outbox;
    private final int defaultTtlSeconds;
    private final String ledgerTopic;

    public ReservationManager(Reservations reservations, MemberRepository members,
                              LedgerService ledger, OutboxRelay outbox, CoreProperties props) {
        this.reservations = reservations;
        this.members = members;
        this.ledger = ledger;
        this.outbox = outbox;
        this.defaultTtlSeconds = props.reservation().defaultTtlSeconds();
        this.ledgerTopic = props.topics().ledgerEvents();
    }

    /** Phase 1 — reserve points. Idempotent on {@code idempotencyKey}. */
    @Transactional
    public Reservation reserve(long memberId, long programId, long points, Long rewardId,
                               Integer ttlSeconds, String idempotencyKey) {
        if (idempotencyKey != null) {
            var existing = reservations.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) return existing.get();
        }
        // Member lock unchanged (ADR-0001): read the balance under the Member's pessimistic lock.
        Member member = members.findByIdForUpdate(memberId)
                .orElseThrow(() -> CoreException.notFound("MEMBER_NOT_FOUND", "unknown memberId=" + memberId));

        long heldTotal = reservations.sumActiveHeld(memberId, Instant.now());
        if (!RedeemableBalance.canRedeem(member.getRedeemableBalance(), heldTotal, points)) {
            throw CoreException.conflict("BALANCE_INSUFFICIENT",
                    "effective balance "
                            + RedeemableBalance.effective(member.getRedeemableBalance(), heldTotal)
                            + " < requested " + points);
        }

        long ttl = ttlSeconds != null ? ttlSeconds : defaultTtlSeconds;
        Instant heldUntil = Instant.now().plus(ttl, ChronoUnit.SECONDS);
        Reservation r = reservations.save(
                Reservation.hold(memberId, programId, points, rewardId, idempotencyKey, heldUntil));
        log.debug("reserved reservationId={} memberId={} points={}", r.reservationId(), memberId, points);
        return r;
    }

    /** Outcome of a commit: the committed reservation plus the Redeemed Ledger entry it wrote. */
    public record CommitResult(Reservation reservation, Long ledgerEntryId) {
    }

    /** Phase 2a — commit: write the {@code Redeemed} Ledger entry. Idempotent on the reservation. */
    @Transactional
    public CommitResult commit(long reservationId, String externalRef) {
        Reservation r = reservations.findForUpdate(reservationId)
                .orElseThrow(() -> CoreException.notFound("RESERVATION_NOT_FOUND", "id=" + reservationId));
        if (r.isCommitted()) {
            return new CommitResult(r, null);   // idempotent replay (entry id was returned on the first commit)
        }
        if (!r.isHeld()) {
            throw CoreException.conflict("RESERVATION_NOT_HELD", "status=" + r.status());
        }
        AppendResult redeemed = ledger.appendRedeemed(
                r.memberId(), r.programId(), r.points(), "reservation-" + reservationId);
        r.commit(externalRef);
        reservations.save(r);
        outbox.enqueue("ledger", "loyalty.ledger.RedemptionCommitted", ledgerTopic,
                String.valueOf(r.memberId()),
                LedgerEvent.of("RedemptionCommitted", r.memberId(), r.programId(),
                        redeemed.entry().entryId(), EntryType.Redeemed, 0, -r.points(),
                        "reservation-" + reservationId, Instant.now()));
        log.debug("committed reservationId={} ledgerEntryId={}", reservationId, redeemed.entry().entryId());
        return new CommitResult(r, redeemed.entry().entryId());
    }

    /** Phase 2b — release: restore the hold, no Ledger entry. Idempotent. Also used by the TTL Sweeper. */
    @Transactional
    public Reservation release(long reservationId, String reason) {
        Reservation r = reservations.findForUpdate(reservationId)
                .orElseThrow(() -> CoreException.notFound("RESERVATION_NOT_FOUND", "id=" + reservationId));
        if (r.isReleased()) {
            return r;   // idempotent replay
        }
        if (!r.isHeld()) {
            throw CoreException.conflict("RESERVATION_NOT_HELD", "status=" + r.status());
        }
        r.release();
        reservations.save(r);
        outbox.enqueue("ledger", "loyalty.ledger.ReservationReleased", ledgerTopic,
                String.valueOf(r.memberId()),
                LedgerEvent.of("ReservationReleased", r.memberId(), r.programId(), reservationId,
                        EntryType.Redeemed, 0, 0, "reservation-" + reservationId, Instant.now()));
        log.debug("released reservationId={} reason={}", reservationId, reason);
        return r;
    }
}
