package io.github.batnam.loyalty.core.infra.member;

import io.github.batnam.loyalty.core.domain.cohort.OpenCohort;
import io.github.batnam.loyalty.core.domain.member.Member;
import io.github.batnam.loyalty.core.domain.member.MemberStatus;

import java.util.List;

/**
 * Maps between the JPA persistence model ({@code core.member.Member}, the {@code @Entity}) and the
 * pure domain aggregate ({@code core.domain.member.Member}). Hand-written by design —
 * the tracer keeps the dependency surface light; revisit a generator only if mapping volume grows.
 */
final class MemberMapper {

    private MemberMapper() {
    }

    /** Rehydrate the domain aggregate from a (locked) persistence row and its open cohorts. */
    static Member toDomain(io.github.batnam.loyalty.core.member.Member entity, List<OpenCohort> openCohorts) {
        return Member.rehydrate(
                entity.getMemberId(),
                entity.getProgramId(),
                entity.getCustomerId(),
                MemberStatus.valueOf(entity.getStatus().name()),
                entity.getRedeemableBalance(),
                entity.getQualifyingBalance(),
                entity.getCurrentTierCode(),
                entity.getTcsVersionAccepted(),
                openCohorts);
    }
}
