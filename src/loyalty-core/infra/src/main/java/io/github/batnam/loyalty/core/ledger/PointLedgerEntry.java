package io.github.batnam.loyalty.core.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.time.Instant;

/**
 * One immutable row in the Point Ledger (CONTEXT.md "Point Ledger Entry"). Source of truth.
 *
 * <p>{@code @Immutable} tells Hibernate to never issue an UPDATE for this entity; combined with the
 * DB-level append-only trigger (V1 migration) it makes the invariant impossible to violate. The
 * {@code (sourceRef, entryType)} pair is unique — replays are silent no-ops.
 */
@Entity
@Immutable
@Table(name = "point_ledger")
public class PointLedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "entry_id")
    private Long entryId;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Column(name = "program_id", nullable = false)
    private Long programId;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false)
    private EntryType entryType;

    @Column(name = "qualifying_delta", nullable = false)
    private long qualifyingDelta;

    @Column(name = "redeemable_delta", nullable = false)
    private long redeemableDelta;

    @Column(name = "source_ref", nullable = false)
    private String sourceRef;

    @Column(name = "reason")
    private String reason;

    @Column(name = "earn_source_code")
    private String earnSourceCode;

    @Column(name = "currency")
    private String currency;

    @Column(name = "approval_request_id")
    private Long approvalRequestId;

    @Column(name = "occurred_at")
    private Instant occurredAt;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected PointLedgerEntry() {
    }

    private PointLedgerEntry(Long memberId, Long programId, EntryType entryType,
                             long qualifyingDelta, long redeemableDelta, String sourceRef,
                             String reason, String earnSourceCode, String currency,
                             Long approvalRequestId, Instant occurredAt) {
        this.memberId = memberId;
        this.programId = programId;
        this.entryType = entryType;
        this.qualifyingDelta = qualifyingDelta;
        this.redeemableDelta = redeemableDelta;
        this.sourceRef = sourceRef;
        this.reason = reason;
        this.earnSourceCode = earnSourceCode;
        this.currency = currency;
        this.approvalRequestId = approvalRequestId;
        this.occurredAt = occurredAt;
    }

    /** Builder-free factory keeping the ctor private — entries are created via named intents. */
    public static PointLedgerEntry of(Long memberId, Long programId, EntryType entryType,
                                      long qualifyingDelta, long redeemableDelta, String sourceRef,
                                      String reason, String earnSourceCode, String currency,
                                      Long approvalRequestId, Instant occurredAt) {
        return new PointLedgerEntry(memberId, programId, entryType, qualifyingDelta, redeemableDelta,
                sourceRef, reason, earnSourceCode, currency, approvalRequestId, occurredAt);
    }

    public Long getEntryId() { return entryId; }
    public Long getMemberId() { return memberId; }
    public Long getProgramId() { return programId; }
    public EntryType getEntryType() { return entryType; }
    public long getQualifyingDelta() { return qualifyingDelta; }
    public long getRedeemableDelta() { return redeemableDelta; }
    public String getSourceRef() { return sourceRef; }
    public String getReason() { return reason; }
    public String getEarnSourceCode() { return earnSourceCode; }
    public String getCurrency() { return currency; }
    public Long getApprovalRequestId() { return approvalRequestId; }
    public Instant getOccurredAt() { return occurredAt; }
    public Instant getCreatedAt() { return createdAt; }
}
