package io.github.batnam.loyalty.adminbff.api;

import io.github.batnam.loyalty.adminbff.api.dto.AdminDtos.LedgerPage;
import io.github.batnam.loyalty.adminbff.api.dto.AdminDtos.MemberDetail;
import io.github.batnam.loyalty.adminbff.api.dto.AdminDtos.MemberSummary;
import io.github.batnam.loyalty.adminbff.client.MemberClient;
import io.github.batnam.loyalty.adminbff.security.EmployeeIdentity;
import io.github.batnam.loyalty.adminbff.security.Roles;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Members (loyalty-admin-bff.yaml). Member lookup, detail, and the Point Ledger audit view, aggregated
 * from loyalty-core. Read-only — visible to CS and read-only roles.
 */
@RestController
public class MemberController {

    private final MemberClient members;

    public MemberController(MemberClient members) {
        this.members = members;
    }

    @GetMapping("/members")
    public List<MemberSummary> findMembers(EmployeeIdentity caller, @RequestParam long customerId) {
        caller.requireAnyRole(Roles.CS_MAKER, Roles.CS_CHECKER, Roles.READONLY);
        return members.findMembers(customerId);
    }

    @GetMapping("/members/{memberId}")
    public MemberDetail getMember(EmployeeIdentity caller, @PathVariable long memberId) {
        caller.requireAnyRole(Roles.CS_MAKER, Roles.CS_CHECKER, Roles.READONLY);
        return members.getMember(memberId);
    }

    @GetMapping("/members/{memberId}/programs/{programId}/ledger")
    public LedgerPage getMemberLedger(EmployeeIdentity caller, @PathVariable long memberId,
                                      @PathVariable long programId,
                                      @RequestParam(required = false) String cursor,
                                      @RequestParam(required = false) Integer limit) {
        caller.requireAnyRole(Roles.CS_MAKER, Roles.CS_CHECKER, Roles.READONLY);
        return members.getMemberLedger(memberId, programId, cursor, limit);
    }
}
