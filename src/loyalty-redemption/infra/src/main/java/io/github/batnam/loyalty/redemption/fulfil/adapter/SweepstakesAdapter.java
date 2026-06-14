package io.github.batnam.loyalty.redemption.fulfil.adapter;

import io.github.batnam.loyalty.redemption.fulfil.FulfilmentResult;
import io.github.batnam.loyalty.redemption.fulfil.FulfillmentAdapter;
import io.github.batnam.loyalty.redemption.fulfil.RewardType;
import io.github.batnam.loyalty.redemption.fulfil.SagaContext;
import io.github.batnam.loyalty.redemption.fulfil.client.CampaignClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Synchronous Sweepstakes adapter (L3 §3.2): records a drawing entry in {@code loyalty-campaign} instead
 * of disbursing — same reserve→fulfil→commit shape as Cashback. {@code drawingId} comes from the
 * Reward's fulfillment params. Returns SUCCESS with the entry ref, or FAILURE on a campaign error.
 */
@Component
public class SweepstakesAdapter implements FulfillmentAdapter {

    private static final Logger log = LoggerFactory.getLogger(SweepstakesAdapter.class);

    private final CampaignClient campaign;

    public SweepstakesAdapter(CampaignClient campaign) {
        this.campaign = campaign;
    }

    @Override
    public RewardType supportedType() {
        return RewardType.SWEEPSTAKES;
    }

    @Override
    public FulfilmentResult fulfil(SagaContext ctx) {
        long drawingId = asLong(ctx.param("drawingId"));
        String idempotencyKey = "saga-" + ctx.sagaId() + "-drawing-" + drawingId;
        try {
            String ref = campaign.recordEntry(drawingId, ctx.memberId(), ctx.sagaId(), idempotencyKey, weight(ctx));
            return FulfilmentResult.success(ref);
        } catch (RuntimeException e) {
            log.warn("sweepstakes entry failed for saga {}: {}", ctx.sagaId(), e.toString());
            return FulfilmentResult.failure("campaign error: " + e.getMessage());
        }
    }

    private static long asLong(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        throw new IllegalArgumentException("sweepstakes reward requires a numeric 'drawingId' fulfillment param");
    }

    /** Optional per-entry weight from the Reward's fulfillment params; null unless the Drawing is WEIGHTED. */
    private static Integer weight(SagaContext ctx) {
        return ctx.param("weight") instanceof Number n ? n.intValue() : null;
    }
}
