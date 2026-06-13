package io.github.batnam.loyalty.redemption.fulfil;

/**
 * The outcome an adapter returns from {@code fulfil()} / {@code resume()} (L3 §4):
 * <ul>
 *   <li>{@code SUCCESS} — fulfilment done in-process; {@code externalRef} is the adapter reference
 *       (Payment Hub disbursement id, voucher code, drawing entry id). Saga commits.</li>
 *   <li>{@code PENDING} — handed off to a partner; {@code externalRef} keys the later resume. Saga
 *       parks at {@code FULFILLING}.</li>
 *   <li>{@code FAILURE} — fulfilment failed; {@code detail} explains. Saga releases the reservation.</li>
 * </ul>
 */
public record FulfilmentResult(Kind kind, String externalRef, String detail) {

    public enum Kind { SUCCESS, PENDING, FAILURE }

    public static FulfilmentResult success(String externalRef) {
        return new FulfilmentResult(Kind.SUCCESS, externalRef, null);
    }

    public static FulfilmentResult pending(String externalRef) {
        return new FulfilmentResult(Kind.PENDING, externalRef, null);
    }

    public static FulfilmentResult failure(String detail) {
        return new FulfilmentResult(Kind.FAILURE, null, detail);
    }

    public boolean isSuccess() { return kind == Kind.SUCCESS; }
    public boolean isPending() { return kind == Kind.PENDING; }
    public boolean isFailure() { return kind == Kind.FAILURE; }
}
