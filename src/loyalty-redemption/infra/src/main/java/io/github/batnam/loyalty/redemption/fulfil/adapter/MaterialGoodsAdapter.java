package io.github.batnam.loyalty.redemption.fulfil.adapter;

import io.github.batnam.loyalty.redemption.fulfil.FulfilmentResult;
import io.github.batnam.loyalty.redemption.fulfil.FulfillmentAdapter;
import io.github.batnam.loyalty.redemption.fulfil.RewardType;
import io.github.batnam.loyalty.redemption.fulfil.SagaContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Synchronous Material-Goods adapter (L3 §3.2). A Material-Goods reward ships a physical item to the
 * Member; in production this would lodge a fulfilment order with a logistics/3PL partner.
 *
 * <p><b>Scope:</b> no logistics partner integration exists in v1 (none is in scope), so this is a
 * <b>stub fulfilment</b> — like {@link BillPaymentVoucherAdapter}'s deferred S3 PDF. It emits a
 * deterministic synthetic shipment id derived from the saga so the reserve→fulfil→commit flow is
 * exercised end-to-end without the partner dependency; swapping in the real partner call is a localized
 * change that returns the partner's shipment reference instead.
 *
 * <p><b>Identity (when the real call lands):</b> the shipment is addressed by {@code ctx.customerId()}
 * (the CIF) and the Member's delivery details — never Loyalty's internal {@code memberId}.
 */
@Component
public class MaterialGoodsAdapter implements FulfillmentAdapter {

    private static final Logger log = LoggerFactory.getLogger(MaterialGoodsAdapter.class);

    @Override
    public RewardType supportedType() {
        return RewardType.MATERIAL_GOODS;
    }

    @Override
    public FulfilmentResult fulfil(SagaContext ctx) {
        // Deterministic, side-effect-free synthetic shipment id (logistics partner deferred).
        String shipmentId = "SHP-" + ctx.rewardId() + "-" + ctx.sagaId();
        log.info("material-goods stub fulfilment for saga {} (reward {}) -> shipment {}",
                ctx.sagaId(), ctx.rewardId(), shipmentId);
        return FulfilmentResult.success(shipmentId);
    }
}
