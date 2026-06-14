package io.github.batnam.loyalty.core.approval;

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
 * Approval request for a Manual Adjustment (CONTEXT.md "Manual Adjustment", "Maker-Checker"; L3 §5).
 * The 4-eyes itself runs in BEP's Approval Workflow — core stores only {@code bepApprovalRef} and
 * the applied {@code Adjusted} entry links back via {@code point_ledger.approval_request_id}.
 */
@Entity
@Table(name = "approval_request")
public class ApprovalRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "approval_request_id")
    private Long approvalRequestId;

    @Column(name = "member_id", nullable = false, updatable = false)
    private Long memberId;

    @Column(name = "program_id", nullable = false, updatable = false)
    private Long programId;

    @Column(name = "request_type", nullable = false, updatable = false)
    private String requestType;

    @Column(name = "qualifying_delta")
    private Long qualifyingDelta;

    @Column(name = "redeemable_delta")
    private Long redeemableDelta;

    @Column(name = "reason")
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ApprovalStatus status = ApprovalStatus.PENDING;

    @Column(name = "bep_approval_ref")
    private String bepApprovalRef;

    @Column(name = "ledger_entry_id")
    private Long ledgerEntryId;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.EPOCH;

    protected ApprovalRequest() {
    }

    public static ApprovalRequest adjustment(Long memberId, Long programId, Long qualifyingDelta,
                                             Long redeemableDelta, String reason) {
        ApprovalRequest a = new ApprovalRequest();
        a.memberId = memberId;
        a.programId = programId;
        a.requestType = "ADJUSTMENT";
        a.qualifyingDelta = qualifyingDelta;
        a.redeemableDelta = redeemableDelta;
        a.reason = reason;
        a.status = ApprovalStatus.PENDING;
        a.updatedAt = Instant.now();
        return a;
    }

    public void apply(String bepApprovalRef, Long ledgerEntryId) {
        this.status = ApprovalStatus.APPLIED;
        this.bepApprovalRef = bepApprovalRef;
        this.ledgerEntryId = ledgerEntryId;
        this.updatedAt = Instant.now();
    }

    public void reject(String bepApprovalRef) {
        this.status = ApprovalStatus.REJECTED;
        this.bepApprovalRef = bepApprovalRef;
        this.updatedAt = Instant.now();
    }

    public boolean isPending() { return status == ApprovalStatus.PENDING; }

    public Long getApprovalRequestId() { return approvalRequestId; }
    public Long getMemberId() { return memberId; }
    public Long getProgramId() { return programId; }
    public String getRequestType() { return requestType; }
    public Long getQualifyingDelta() { return qualifyingDelta; }
    public Long getRedeemableDelta() { return redeemableDelta; }
    public String getReason() { return reason; }
    public ApprovalStatus getStatus() { return status; }
    public String getBepApprovalRef() { return bepApprovalRef; }
    public Long getLedgerEntryId() { return ledgerEntryId; }
}
