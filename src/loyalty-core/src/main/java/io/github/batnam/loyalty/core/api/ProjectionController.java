package io.github.batnam.loyalty.core.api;

import io.github.batnam.loyalty.core.api.dto.MemberDtos.MemberLookupResponse;
import io.github.batnam.loyalty.core.api.dto.MemberDtos.MemberProjectionResponse;
import io.github.batnam.loyalty.core.error.CoreException;
import io.github.batnam.loyalty.core.member.Member;
import io.github.batnam.loyalty.core.member.MemberRepository;
import io.github.batnam.loyalty.core.program.ProgramConfigService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Projection API (loyalty-core.yaml, tag Projections). Read-side balance + tier projection consulted
 * by {@code loyalty-redemption}'s Eligibility Engine before a reserve, plus the lightweight member
 * lookup {@code loyalty-earning} uses to resolve a customer-scoped EarnEvent to a memberId.
 */
@RestController
public class ProjectionController {

    private final MemberRepository members;
    private final ProgramConfigService programConfig;

    public ProjectionController(MemberRepository members, ProgramConfigService programConfig) {
        this.members = members;
        this.programConfig = programConfig;
    }

    @GetMapping("/members/{memberId}/programs/{programId}/projection")
    public MemberProjectionResponse projection(@PathVariable long memberId, @PathVariable long programId) {
        Member m = members.findById(memberId)
                .filter(member -> member.getProgramId().equals(programId))
                .orElseThrow(() -> CoreException.notFound("MEMBER_NOT_FOUND",
                        "member " + memberId + " not enrolled in program " + programId));
        return MemberProjectionResponse.from(m);
    }

    @GetMapping("/members/lookup")
    public MemberLookupResponse lookup(@RequestParam long programId, @RequestParam long customerId) {
        Member m = members.findByProgramIdAndCustomerId(programId, customerId)
                .orElseThrow(() -> CoreException.notFound("MEMBER_NOT_FOUND",
                        "no member for program " + programId + " customer " + customerId));
        return MemberLookupResponse.from(m,
                programConfig.earnMultiplierFor(m.getProgramId(), m.getCurrentTierCode()));
    }
}
