package io.github.batnam.loyalty.core.api.dto;

import io.github.batnam.loyalty.core.member.Member;
import io.github.batnam.loyalty.core.member.MemberStatus;

import java.time.Instant;

/** Membership + projection DTOs. */
public final class MemberDtos {

    private MemberDtos() {
    }

    public record OptInRequest(Long programId, Long customerId, Integer tcsVersionAccepted) {
    }

    public record MemberResponse(
            Long memberId, Long programId, Long customerId, MemberStatus status,
            long redeemableBalance, long qualifyingBalance, String tierCode) {

        public static MemberResponse from(Member m) {
            return new MemberResponse(m.getMemberId(), m.getProgramId(), m.getCustomerId(), m.getStatus(),
                    m.getRedeemableBalance(), m.getQualifyingBalance(), m.getCurrentTierCode());
        }
    }

    /**
     * Lightweight resolution of {@code (programId, customerId)} to a Member, used by
     * {@code loyalty-earning}'s Member Resolver to turn a customer-scoped EarnEvent into a memberId
     * before calling the Ledger API. PII-free.
     */
    public record MemberLookupResponse(Long memberId, Long programId, MemberStatus status) {
        public static MemberLookupResponse from(Member m) {
            return new MemberLookupResponse(m.getMemberId(), m.getProgramId(), m.getStatus());
        }
    }

    /** {@code MemberProjection} response (loyalty-core.yaml) for the Eligibility read path. */
    public record MemberProjectionResponse(
            Long memberId, Long programId, long redeemableBalance, long qualifyingBalance,
            String tierCode, MemberStatus status, Instant asOf) {

        public static MemberProjectionResponse from(Member m) {
            return new MemberProjectionResponse(m.getMemberId(), m.getProgramId(),
                    m.getRedeemableBalance(), m.getQualifyingBalance(), m.getCurrentTierCode(),
                    m.getStatus(), Instant.now());
        }
    }
}
