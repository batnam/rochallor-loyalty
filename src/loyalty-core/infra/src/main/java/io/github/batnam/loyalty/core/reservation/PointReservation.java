package io.github.batnam.loyalty.core.reservation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A short-lived hold on a Member's Redeemable Balance (CONTEXT.md "Reservation"). Mutable — NOT a
 * Ledger entry (which would violate immutability for transient state). {@code held_until} drives the
 * TTL Sweeper. Only the {@code commit()} transition produces a permanent {@code Redeemed} Ledger entry.
 */
@Entity
@Table(name = "point_reservation")
public class PointReservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "reservation_id")
    private Long reservationId;

    @Column(name = "member_id", nullable = false, updatable = false)
    private Long memberId;

    @Column(name = "program_id", nullable = false, updatable = false)
    private Long programId;

    @Column(name = "points", nullable = false, updatable = false)
    private long points;

    @Column(name = "reward_id", updatable = false)
    private Long rewardId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ReservationStatus status = ReservationStatus.HELD;

    @Column(name = "external_ref")
    private String externalRef;

    @Column(name = "idempotency_key", updatable = false)
    private String idempotencyKey;

    @Column(name = "held_until", nullable = false)
    private Instant heldUntil;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.EPOCH;

    protected PointReservation() {
    }

    public static PointReservation hold(Long memberId, Long programId, long points, Long rewardId,
                                        String idempotencyKey, Instant heldUntil) {
        PointReservation r = new PointReservation();
        r.memberId = memberId;
        r.programId = programId;
        r.points = points;
        r.rewardId = rewardId;
        r.idempotencyKey = idempotencyKey;
        r.heldUntil = heldUntil;
        r.status = ReservationStatus.HELD;
        r.updatedAt = Instant.now();
        return r;
    }

    public void commit(String externalRef) {
        this.status = ReservationStatus.COMMITTED;
        this.externalRef = externalRef;
        this.updatedAt = Instant.now();
    }

    public void release() {
        this.status = ReservationStatus.RELEASED;
        this.updatedAt = Instant.now();
    }

    public void expire() {
        this.status = ReservationStatus.EXPIRED;
        this.updatedAt = Instant.now();
    }

    public boolean isHeld() { return status == ReservationStatus.HELD; }

    public Long getReservationId() { return reservationId; }
    public Long getMemberId() { return memberId; }
    public Long getProgramId() { return programId; }
    public long getPoints() { return points; }
    public Long getRewardId() { return rewardId; }
    public ReservationStatus getStatus() { return status; }
    public String getExternalRef() { return externalRef; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public Instant getHeldUntil() { return heldUntil; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
