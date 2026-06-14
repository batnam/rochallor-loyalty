package io.github.batnam.loyalty.earning.ledger;

import java.time.Instant;

/**
 * Body of {@code POST /ledger/earn} on loyalty-core (loyalty-core.yaml {@code EarnRequest}). Field
 * names must serialize exactly as core's record expects. {@code sourceRef = eventId + ":" + ruleId}
 * makes every rule fire individually idempotent and reversible (L3 §3.2).
 */
public record LedgerEarnRequest(
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
