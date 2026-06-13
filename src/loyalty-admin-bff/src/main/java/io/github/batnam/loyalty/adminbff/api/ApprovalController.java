package io.github.batnam.loyalty.adminbff.api;

import io.github.batnam.loyalty.adminbff.api.dto.AdminDtos.ApprovalConfirmRequest;
import io.github.batnam.loyalty.adminbff.api.dto.AdminDtos.ApprovalCreateRequest;
import io.github.batnam.loyalty.adminbff.api.dto.AdminDtos.ApprovalRequest;
import io.github.batnam.loyalty.adminbff.client.ApprovalClient;
import io.github.batnam.loyalty.adminbff.security.EmployeeIdentity;
import io.github.batnam.loyalty.adminbff.security.Roles;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Approvals (loyalty-admin-bff.yaml). Money-equivalent and economic-config changes are raised here as
 * {@code PENDING} and applied via {@code confirm} once the bank's BEP Approval Workflow decides. Loyalty
 * does <strong>not</strong> implement maker-checker — BEP owns routing, Job Roles, 4-eyes, and caps. The
 * BFF forwards to loyalty-core's approval store (it keeps no state of its own).
 */
@RestController
public class ApprovalController {

    private final ApprovalClient approvals;

    public ApprovalController(ApprovalClient approvals) {
        this.approvals = approvals;
    }

    @PostMapping("/approval-requests")
    public ResponseEntity<ApprovalRequest> create(EmployeeIdentity caller,
                                                  @RequestBody ApprovalCreateRequest req) {
        caller.requireAnyRole(Roles.CS_MAKER, Roles.CAMPAIGN_MANAGER);
        return ResponseEntity.status(HttpStatus.CREATED).body(approvals.create(caller.userId(), req));
    }

    @GetMapping("/approval-requests")
    public List<ApprovalRequest> list(EmployeeIdentity caller,
                                      @RequestParam(required = false, defaultValue = "PENDING") String status,
                                      @RequestParam(required = false) String type) {
        caller.requireAnyRole(Roles.CS_MAKER, Roles.CS_CHECKER, Roles.CAMPAIGN_MANAGER, Roles.READONLY);
        return approvals.list(status, type);
    }

    /**
     * Applied/rejected on BEP's decision. The hardened confirm seam is authenticated by mTLS service
     * identity + a verifiable BEP approval assertion (Arch §7.1); it is not gated by an employee role
     * here — the {@code bepApprovalRef} in the body is the system-of-record for who approved.
     */
    @PostMapping("/approval-requests/{requestId}/confirm")
    public ApprovalRequest confirm(EmployeeIdentity caller, @PathVariable long requestId,
                                   @RequestBody ApprovalConfirmRequest req) {
        return approvals.confirm(requestId, req);
    }
}
