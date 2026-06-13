package io.github.batnam.loyalty.redemption.fulfil.adapter;

import io.github.batnam.loyalty.redemption.fulfil.FulfilmentResult;
import io.github.batnam.loyalty.redemption.fulfil.FulfillmentAdapter;
import io.github.batnam.loyalty.redemption.fulfil.PartnerOutcome;
import io.github.batnam.loyalty.redemption.fulfil.RewardType;
import io.github.batnam.loyalty.redemption.fulfil.SagaContext;
import io.github.batnam.loyalty.redemption.fulfil.client.VoucherPartnerClient;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Asynchronous 3rd-Party Voucher adapter (L3 §3.3) — the one path that breaks the in-process Saga.
 * {@code fulfil()} submits to the partner and returns PENDING(externalRef); the Saga parks at FULFILLING.
 * When the partner webhook lands (bridge → {@code loyalty.fulfillment.resume.v1} → Resume Consumer),
 * {@code resume()} turns the partner outcome into SUCCESS (commit) or FAILURE (release).
 */
@Component
public class ThirdPartyVoucherAdapter implements FulfillmentAdapter {

    private final VoucherPartnerClient partner;

    public ThirdPartyVoucherAdapter(VoucherPartnerClient partner) {
        this.partner = partner;
    }

    @Override
    public RewardType supportedType() {
        return RewardType.THIRD_PARTY_VOUCHER;
    }

    @Override
    public FulfilmentResult fulfil(SagaContext ctx) {
        String sku = ctx.param("sku") == null ? "UNKNOWN-SKU" : ctx.param("sku").toString();
        try {
            String ref = partner.provision(sku, "saga-" + ctx.sagaId());
            return FulfilmentResult.pending(ref);   // park; the webhook resumes the Saga later
        } catch (RuntimeException e) {
            return FulfilmentResult.failure("voucher partner submit failed: " + e.getMessage());
        }
    }

    @Override
    public Optional<FulfilmentResult> resume(String externalRef, PartnerOutcome outcome) {
        if (!outcome.success()) {
            return Optional.of(FulfilmentResult.failure("partner reported provisioning failure"));
        }
        // Prefer the issued voucher code from the payload; fall back to the correlation ref.
        Object voucherCode = outcome.payload() == null ? null : outcome.payload().get("voucherCode");
        return Optional.of(FulfilmentResult.success(
                voucherCode != null ? voucherCode.toString() : externalRef));
    }
}
