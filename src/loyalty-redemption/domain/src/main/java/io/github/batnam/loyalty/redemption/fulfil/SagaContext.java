package io.github.batnam.loyalty.redemption.fulfil;

import java.util.Map;

/**
 * The read-only context the Saga hands an adapter's {@code fulfil()} (L3 §4). Carries the identifiers an
 * adapter needs to act and the Reward's {@code fulfillmentParams} (e.g. {@code amount}/{@code currency}
 * for Cashback, {@code sku} for a voucher, {@code drawingId} for Sweepstakes). Adapters never write
 * {@code redemption_saga} — they return a {@link FulfilmentResult}; the Saga records it.
 *
 * <p>Identity: {@code memberId} is Loyalty-internal (ledger/saga, Sweepstakes campaign entry).
 * {@code customerId} (the bank's CIF) and {@code accountNumber} (the target CASA the customer chose on
 * the Mobile App) are the bank-facing identifiers a Host Bank adapter — e.g. Cashback's Payment Hub
 * disbursement — needs; {@code accountNumber} is null for reward types that don't credit a CASA.
 */
public record SagaContext(
        Long sagaId,
        long programId,
        long memberId,
        long customerId,
        String accountNumber,
        long rewardId,
        long pointCost,
        Map<String, Object> fulfillmentParams
) {
    public Object param(String key) {
        return fulfillmentParams == null ? null : fulfillmentParams.get(key);
    }
}
