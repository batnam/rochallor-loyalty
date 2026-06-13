package io.github.batnam.loyalty.redemption.fulfil.adapter;

import io.github.batnam.loyalty.redemption.fulfil.FulfilmentResult;
import io.github.batnam.loyalty.redemption.fulfil.FulfillmentAdapter;
import io.github.batnam.loyalty.redemption.fulfil.RewardType;
import io.github.batnam.loyalty.redemption.fulfil.SagaContext;
import org.springframework.stereotype.Component;

/**
 * Synchronous Bill-Payment Voucher adapter (L3 §3.2). Generates a voucher artifact in-process and
 * returns SUCCESS immediately.
 *
 * <p><b>Scope:</b> real S3 PDF rendering + pre-signed URL is deferred (L3 out-of-scope, like core's S3
 * WORM defer). This emits a deterministic synthetic voucher code derived from the saga so the flow is
 * exercised end-to-end without the S3 dependency; swapping in the real S3 put is a localized change.
 *
 * <p><b>Identity (when the real call lands):</b> a bill-payment voucher is settled against a Host Bank
 * capability, so the real artifact/disbursement must be addressed by {@code ctx.customerId()} (the CIF)
 * and, if it credits/debits an account, {@code ctx.accountNumber()} — never Loyalty's internal
 * {@code memberId}. Both are already on {@link SagaContext}; the synthetic code below carries none yet.
 */
@Component
public class BillPaymentVoucherAdapter implements FulfillmentAdapter {

    @Override
    public RewardType supportedType() {
        return RewardType.BILL_PAYMENT_VOUCHER;
    }

    @Override
    public FulfilmentResult fulfil(SagaContext ctx) {
        // Deterministic, side-effect-free synthetic voucher code (S3 PDF deferred).
        String voucherCode = "VCH-" + ctx.rewardId() + "-" + ctx.sagaId();
        return FulfilmentResult.success(voucherCode);
    }
}
