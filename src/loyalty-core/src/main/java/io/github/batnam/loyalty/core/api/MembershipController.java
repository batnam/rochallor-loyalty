package io.github.batnam.loyalty.core.api;

import io.github.batnam.loyalty.core.api.dto.MemberDtos.MemberResponse;
import io.github.batnam.loyalty.core.api.dto.MemberDtos.OptInRequest;
import io.github.batnam.loyalty.core.api.dto.MemberDtos.TcsAcceptanceRequest;
import io.github.batnam.loyalty.core.member.MembershipAggregate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Membership write surface used by the BFFs (L3 §6 — mobile/admin-bff → Membership Aggregate).
 * Complements the read-side projection; opt-in is the act that creates a Member from a Customer.
 */
@RestController
@RequestMapping("/members")
public class MembershipController {

    private final MembershipAggregate membership;

    public MembershipController(MembershipAggregate membership) {
        this.membership = membership;
    }

    @PostMapping
    public ResponseEntity<MemberResponse> optIn(@RequestBody OptInRequest req) {
        var member = membership.optIn(req.programId(), req.customerId(), req.tcsVersionAccepted());
        return ResponseEntity.status(HttpStatus.CREATED).body(MemberResponse.from(member));
    }

    @PostMapping("/{memberId}/opt-out")
    public MemberResponse optOut(@PathVariable long memberId) {
        return MemberResponse.from(membership.optOut(memberId));
    }

    /** Re-accept the current T&Cs version (mobile-bff → core). Lifts a SUSPENDED_TCS hold when caught up. */
    @PostMapping("/{memberId}/tcs-acceptance")
    public MemberResponse acceptTcs(@PathVariable long memberId, @RequestBody TcsAcceptanceRequest req) {
        return MemberResponse.from(membership.acceptTcs(memberId, req.acceptedVersion()));
    }
}
