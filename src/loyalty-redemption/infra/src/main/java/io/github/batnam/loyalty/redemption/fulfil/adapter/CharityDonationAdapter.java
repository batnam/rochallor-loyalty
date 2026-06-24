package io.github.batnam.loyalty.redemption.fulfil.adapter;

import io.github.batnam.loyalty.redemption.fulfil.FulfilmentResult;
import io.github.batnam.loyalty.redemption.fulfil.FulfillmentAdapter;
import io.github.batnam.loyalty.redemption.fulfil.RewardType;
import io.github.batnam.loyalty.redemption.fulfil.SagaContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Synchronous Charity-Donation adapter (L3 §3.2). A Charity-Donation reward converts the Member's points
 * into a donation to a partner charity; in production this would lodge the donation with the charity /
 * payment partner and capture its receipt.
 *
 * <p><b>Scope:</b> no charity/payment partner integration exists in v1 (none is in scope), so this is a
 * <b>stub fulfilment</b> — like {@link BillPaymentVoucherAdapter}'s deferred S3 PDF. It emits a
 * deterministic synthetic donation receipt id derived from the saga so the reserve→fulfil→commit flow is
 * exercised end-to-end without the partner dependency; swapping in the real partner call is a localized
 * change that returns the partner's receipt reference instead.
 */
@Component
public class CharityDonationAdapter implements FulfillmentAdapter {

    private static final Logger log = LoggerFactory.getLogger(CharityDonationAdapter.class);

    @Override
    public RewardType supportedType() {
        return RewardType.CHARITY_DONATION;
    }

    @Override
    public FulfilmentResult fulfil(SagaContext ctx) {
        // Deterministic, side-effect-free synthetic donation receipt id (charity partner deferred).
        String receiptId = "DON-" + ctx.rewardId() + "-" + ctx.sagaId();
        log.info("charity-donation stub fulfilment for saga {} (reward {}) -> receipt {}",
                ctx.sagaId(), ctx.rewardId(), receiptId);
        return FulfilmentResult.success(receiptId);
    }
}
