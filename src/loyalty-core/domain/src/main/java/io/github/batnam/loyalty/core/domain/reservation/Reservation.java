package io.github.batnam.loyalty.core.domain.reservation;

import java.time.Instant;

/**
 * The Reservation aggregate (CONTEXT.md "Reservation"): a short-lived hold on a Member's Effective
 * Redeemable Balance, with its own lifecycle (HELD → COMMITTED | RELEASED | EXPIRED) and — per
 *  — its own lock, deliberately separate from the Member aggregate. Pure domain, no JPA.
 *
 * <p>A HELD reservation does <i>not</i> mutate {@code member.redeemable_balance}; only a commit's
 * {@code Redeemed} Ledger entry does. The {@code :infra} adapter persists transitions against the
 * locked {@code point_reservation} row.
 */
public final class Reservation {

    private final Long reservationId;   // null until persisted
    private final long memberId;
    private final long programId;
    private final long points;
    private final Long rewardId;
    private final String idempotencyKey;
    private ReservationStatus status;
    private String externalRef;
    private final Instant heldUntil;

    private Reservation(Long reservationId, long memberId, long programId, long points, Long rewardId,
                        String idempotencyKey, ReservationStatus status, String externalRef, Instant heldUntil) {
        this.reservationId = reservationId;
        this.memberId = memberId;
        this.programId = programId;
        this.points = points;
        this.rewardId = rewardId;
        this.idempotencyKey = idempotencyKey;
        this.status = status;
        this.externalRef = externalRef;
        this.heldUntil = heldUntil;
    }

    /** Create a new HELD reservation (not yet persisted, so {@link #reservationId()} is null). */
    public static Reservation hold(long memberId, long programId, long points, Long rewardId,
                                   String idempotencyKey, Instant heldUntil) {
        return new Reservation(null, memberId, programId, points, rewardId, idempotencyKey,
                ReservationStatus.HELD, null, heldUntil);
    }

    /** Reconstruct an existing reservation from its (locked) persistence row. */
    public static Reservation rehydrate(long reservationId, long memberId, long programId, long points,
                                        Long rewardId, String idempotencyKey, ReservationStatus status,
                                        String externalRef, Instant heldUntil) {
        return new Reservation(reservationId, memberId, programId, points, rewardId, idempotencyKey,
                status, externalRef, heldUntil);
    }

    /** Phase 2a — commit the hold (its {@code Redeemed} Ledger entry is written by the caller). */
    public void commit(String externalRef) {
        this.status = ReservationStatus.COMMITTED;
        this.externalRef = externalRef;
    }

    /** Phase 2b — release the hold (no Ledger entry). Also used by the TTL sweeper. */
    public void release() {
        this.status = ReservationStatus.RELEASED;
    }

    public void expire() {
        this.status = ReservationStatus.EXPIRED;
    }

    public boolean isNew() { return reservationId == null; }
    public boolean isHeld() { return status == ReservationStatus.HELD; }
    public boolean isCommitted() { return status == ReservationStatus.COMMITTED; }
    public boolean isReleased() { return status == ReservationStatus.RELEASED; }

    public Long reservationId() { return reservationId; }
    public long memberId() { return memberId; }
    public long programId() { return programId; }
    public long points() { return points; }
    public Long rewardId() { return rewardId; }
    public String idempotencyKey() { return idempotencyKey; }
    public ReservationStatus status() { return status; }
    public String externalRef() { return externalRef; }
    public Instant heldUntil() { return heldUntil; }
}
