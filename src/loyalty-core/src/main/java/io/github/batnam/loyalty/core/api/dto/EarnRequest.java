package io.github.batnam.loyalty.core.api.dto;

import java.time.Instant;

/** {@code POST /ledger/earn} body (loyalty-core.yaml {@code EarnRequest}). */
public record EarnRequest(
        Long memberId,
        Long programId,
        String earnSourceCode,
        String sourceRef,
        Long qualifyingDelta,
        Long redeemableDelta,
        String currency,
        Instant occurredAt
) {
}
