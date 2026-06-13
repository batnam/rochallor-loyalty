package io.github.batnam.loyalty.core.api;

import io.github.batnam.loyalty.core.api.dto.ApprovalDtos.ApprovalResponse;
import io.github.batnam.loyalty.core.api.dto.ApprovalDtos.ConfirmRequest;
import io.github.batnam.loyalty.core.api.dto.ApprovalDtos.CreateApprovalRequest;
import io.github.batnam.loyalty.core.approval.ApprovalRequestStore;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin approval surface used by {@code loyalty-admin-bff} (L3 §6.3). Maker creates an adjustment
 * request; the 4-eyes runs in BEP's Approval Workflow; the confirm seam carries {@code bepApprovalRef}
 * and is the only path that writes an {@code Adjusted} Ledger entry. {@code X-Actor-Id} is the
 * BEP operator's identity forwarded by the Admin BFF (recorded in the audit log).
 */
@RestController
@RequestMapping("/approval-requests")
public class AdminApprovalController {

    private final ApprovalRequestStore approvals;

    public AdminApprovalController(ApprovalRequestStore approvals) {
        this.approvals = approvals;
    }

    @PostMapping
    public ResponseEntity<ApprovalResponse> create(
            @RequestHeader(value = "X-Actor-Id", defaultValue = "system") String actor,
            @RequestBody CreateApprovalRequest req) {
        var created = approvals.create(actor, req.memberId(), req.programId(),
                req.qualifyingDelta(), req.redeemableDelta(), req.reason());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApprovalResponse.from(created));
    }

    @PostMapping("/{id}/confirm")
    public ApprovalResponse confirm(
            @RequestHeader(value = "X-Actor-Id", defaultValue = "system") String actor,
            @PathVariable long id, @RequestBody ConfirmRequest req) {
        return ApprovalResponse.from(approvals.confirm(actor, id, req.bepApprovalRef()));
    }

    @PostMapping("/{id}/reject")
    public ApprovalResponse reject(
            @RequestHeader(value = "X-Actor-Id", defaultValue = "system") String actor,
            @PathVariable long id, @RequestBody ConfirmRequest req) {
        return ApprovalResponse.from(approvals.reject(actor, id, req.bepApprovalRef()));
    }
}
