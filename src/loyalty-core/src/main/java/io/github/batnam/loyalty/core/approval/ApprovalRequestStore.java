package io.github.batnam.loyalty.core.approval;

import io.github.batnam.loyalty.core.audit.AuditLogWriter;
import io.github.batnam.loyalty.core.error.CoreException;
import io.github.batnam.loyalty.core.ledger.AppendResult;
import io.github.batnam.loyalty.core.ledger.LedgerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Approval Request Store (L3 §4, component 8). Holds the adjustment request lifecycle
 * ({@code PENDING → APPLIED | REJECTED}). The only legal outcome of a confirmed request is an
 * {@code Adjusted} Ledger entry, so the lifecycle lives here (a Ledger concern), not in the BFF.
 * 4-eyes is delegated to BEP — {@code confirm} requires a {@code bepApprovalRef} (DD-9).
 */
@Service
public class ApprovalRequestStore {

    private static final Logger log = LoggerFactory.getLogger(ApprovalRequestStore.class);

    private final ApprovalRepository approvals;
    private final LedgerService ledger;
    private final AuditLogWriter audit;

    public ApprovalRequestStore(ApprovalRepository approvals, LedgerService ledger, AuditLogWriter audit) {
        this.approvals = approvals;
        this.ledger = ledger;
        this.audit = audit;
    }

    @Transactional
    public ApprovalRequest create(String actor, long memberId, long programId, Long qualifyingDelta,
                                  Long redeemableDelta, String reason) {
        ApprovalRequest req = approvals.save(ApprovalRequest.adjustment(
                memberId, programId, qualifyingDelta, redeemableDelta, reason));
        audit.record(actor, "ADJUSTMENT_REQUESTED", "approval_request",
                String.valueOf(req.getApprovalRequestId()), null,
                "delta(q=" + qualifyingDelta + ",r=" + redeemableDelta + ") reason=" + reason);
        return req;
    }

    /** Confirm with the BEP Approval Workflow decision — writes the {@code Adjusted} Ledger entry. */
    @Transactional
    public ApprovalRequest confirm(String actor, long approvalRequestId, String bepApprovalRef) {
        if (bepApprovalRef == null || bepApprovalRef.isBlank()) {
            throw CoreException.badRequest("BEP_REF_REQUIRED", "confirm requires a bepApprovalRef (4-eyes via BEP)");
        }
        ApprovalRequest req = require(approvalRequestId);
        if (req.getStatus() == ApprovalStatus.APPLIED) return req;   // idempotent
        if (!req.isPending()) {
            throw CoreException.conflict("APPROVAL_NOT_PENDING", "status=" + req.getStatus());
        }
        long q = req.getQualifyingDelta() != null ? req.getQualifyingDelta() : 0;
        long r = req.getRedeemableDelta() != null ? req.getRedeemableDelta() : 0;
        AppendResult applied = ledger.appendAdjusted(req.getMemberId(), req.getProgramId(), q, r,
                req.getReason(), approvalRequestId, "approval-" + approvalRequestId);
        req.apply(bepApprovalRef, applied.entry().entryId());
        approvals.save(req);
        audit.record(actor, "ADJUSTMENT_APPLIED", "approval_request",
                String.valueOf(approvalRequestId), "PENDING",
                "APPLIED bepRef=" + bepApprovalRef + " ledgerEntryId=" + applied.entry().entryId());
        log.debug("applied approvalRequestId={} ledgerEntryId={}", approvalRequestId, applied.entry().entryId());
        return req;
    }

    @Transactional
    public ApprovalRequest reject(String actor, long approvalRequestId, String bepApprovalRef) {
        ApprovalRequest req = require(approvalRequestId);
        if (req.getStatus() == ApprovalStatus.REJECTED) return req;   // idempotent
        if (!req.isPending()) {
            throw CoreException.conflict("APPROVAL_NOT_PENDING", "status=" + req.getStatus());
        }
        req.reject(bepApprovalRef);
        approvals.save(req);
        audit.record(actor, "ADJUSTMENT_REJECTED", "approval_request",
                String.valueOf(approvalRequestId), "PENDING", "REJECTED bepRef=" + bepApprovalRef);
        return req;
    }

    private ApprovalRequest require(long id) {
        return approvals.findById(id)
                .orElseThrow(() -> CoreException.notFound("APPROVAL_NOT_FOUND", "id=" + id));
    }
}
