package io.github.batnam.loyalty.redemption.saga;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * The Saga state row (L3 §5 {@code redemption_saga}) — single-writer = the Orchestrator. The state
 * transitions go through {@link SagaStatus#canTransitionTo}, so an illegal move (e.g. committing a
 * released saga, or resuming a terminal one) throws rather than silently corrupting the machine.
 */
@Entity
@Table(name = "redemption_saga")
public class RedemptionSaga {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "saga_id")
    private Long sagaId;

    @Column(name = "program_id", nullable = false, updatable = false)
    private Long programId;

    @Column(name = "member_id", nullable = false, updatable = false)
    private Long memberId;

    @Column(name = "reward_id", nullable = false, updatable = false)
    private Long rewardId;

    @Column(name = "reward_type_code", nullable = false, updatable = false)
    private String rewardTypeCode;

    @Column(name = "point_cost", nullable = false, updatable = false)
    private long pointCost;

    @Column(name = "reservation_id")
    private Long reservationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private SagaStatus status;

    @Column(name = "external_ref")
    private String externalRef;

    @Column(name = "ledger_entry_id")
    private Long ledgerEntryId;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected RedemptionSaga() {
    }

    /** Open a saga that has just reserved points in core (Phase 1 done). */
    public static RedemptionSaga reserved(Long programId, Long memberId, Long rewardId,
                                          String rewardTypeCode, long pointCost, Long reservationId) {
        RedemptionSaga s = new RedemptionSaga();
        s.programId = programId;
        s.memberId = memberId;
        s.rewardId = rewardId;
        s.rewardTypeCode = rewardTypeCode;
        s.pointCost = pointCost;
        s.reservationId = reservationId;
        s.status = SagaStatus.RESERVED;
        return s;
    }

    private void transitionTo(SagaStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new IllegalStateException("illegal saga transition " + status + " -> " + target
                    + " (saga " + sagaId + ")");
        }
        this.status = target;
    }

    public void beginFulfilling() { transitionTo(SagaStatus.FULFILLING); }

    /** Park awaiting a partner resume — stays FULFILLING, records the correlation ref. */
    public void awaitResume(String externalRef) {
        if (status != SagaStatus.FULFILLING) {
            throw new IllegalStateException("can only await resume while FULFILLING (saga " + sagaId + ")");
        }
        this.externalRef = externalRef;
    }

    public void commit(Long ledgerEntryId, String externalRef) {
        transitionTo(SagaStatus.COMMITTED);
        this.ledgerEntryId = ledgerEntryId;
        if (externalRef != null) {
            this.externalRef = externalRef;
        }
    }

    public void release(String reason) {
        transitionTo(SagaStatus.RELEASED);
        this.failureReason = reason;
    }

    public void fail(String reason) {
        transitionTo(SagaStatus.FAILED);
        this.failureReason = reason;
    }

    @PreUpdate
    void touch() { this.updatedAt = Instant.now(); }

    public Long getSagaId() { return sagaId; }
    public Long getProgramId() { return programId; }
    public Long getMemberId() { return memberId; }
    public Long getRewardId() { return rewardId; }
    public String getRewardTypeCode() { return rewardTypeCode; }
    public long getPointCost() { return pointCost; }
    public Long getReservationId() { return reservationId; }
    public SagaStatus getStatus() { return status; }
    public String getExternalRef() { return externalRef; }
    public Long getLedgerEntryId() { return ledgerEntryId; }
    public String getFailureReason() { return failureReason; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
