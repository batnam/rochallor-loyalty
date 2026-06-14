package io.github.batnam.loyalty.redemption.saga;

/**
 * The Saga's outcome handed back to the API layer. {@code status=COMMITTED} maps to HTTP 200,
 * {@code FULFILLING} (partner hand-off) to 202; {@code replayed} marks an idempotent replay so the API
 * can return the original outcome verbatim.
 */
public record RedemptionResult(
        Long sagaId,
        SagaStatus status,
        Long ledgerEntryId,
        String externalRef,
        boolean replayed
) {
    public static RedemptionResult of(RedemptionSaga s, boolean replayed) {
        return new RedemptionResult(s.getSagaId(), s.getStatus(), s.getLedgerEntryId(),
                s.getExternalRef(), replayed);
    }
}
