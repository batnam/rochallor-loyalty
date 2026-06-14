package io.github.batnam.loyalty.core.api.dto;

import io.github.batnam.loyalty.core.approval.ApprovalRequest;
import io.github.batnam.loyalty.core.approval.ApprovalStatus;

/** Approval request/confirm DTOs (Manual Adjustment via Maker-Checker delegated to BEP). */
public final class ApprovalDtos {

    private ApprovalDtos() {
    }

    public record CreateApprovalRequest(
            Long memberId, Long programId, Long qualifyingDelta, Long redeemableDelta, String reason) {
    }

    public record ConfirmRequest(String bepApprovalRef) {
    }

    public record ApprovalResponse(
            Long approvalRequestId, Long memberId, Long programId, ApprovalStatus status,
            Long qualifyingDelta, Long redeemableDelta, String bepApprovalRef, Long ledgerEntryId) {

        public static ApprovalResponse from(ApprovalRequest a) {
            return new ApprovalResponse(a.getApprovalRequestId(), a.getMemberId(), a.getProgramId(),
                    a.getStatus(), a.getQualifyingDelta(), a.getRedeemableDelta(),
                    a.getBepApprovalRef(), a.getLedgerEntryId());
        }
    }
}
