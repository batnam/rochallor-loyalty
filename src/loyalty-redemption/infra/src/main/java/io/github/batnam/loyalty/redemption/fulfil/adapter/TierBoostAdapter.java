package io.github.batnam.loyalty.redemption.fulfil.adapter;

import io.github.batnam.loyalty.redemption.fulfil.FulfilmentResult;
import io.github.batnam.loyalty.redemption.fulfil.FulfillmentAdapter;
import io.github.batnam.loyalty.redemption.fulfil.RewardType;
import io.github.batnam.loyalty.redemption.fulfil.SagaContext;
import io.github.batnam.loyalty.redemption.ledger.LedgerClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Tier-Boost adapter (L3 §3.2) — a <i>cross-service</i> reward type in shape (like
 * {@code SweepstakesAdapter}): the Member spends redeemable points to gain <b>qualifying</b> points
 * (tier progress) in loyalty-core. The redeemable spend is the points held by the saga's reservation and
 * committed via the normal {@code commit()} (a {@code Redeemed} ledger entry, redeemable −N). The
 * qualifying gain is the {@code qualifyingDelta} below: a separate, qualifying-only ledger entry
 * (qualifyingDelta +N, redeemableDelta 0) appended in core.
 *
 * <p>The qualifying gain is posted to core via {@link LedgerClient#grantQualifying} — core's
 * earning-owned {@code POST /ledger/earn} seam ({@code earnSourceCode=TIER_BOOST}). The grant is keyed
 * on the Saga ({@code tierboost-<sagaId>}) so a Saga retry is an idempotent replay rather than a
 * double-grant, and the returned core entry id becomes the adapter's external ref. On a core failure the
 * adapter returns FAILURE so the Saga releases the reservation.
 */
@Component
public class TierBoostAdapter implements FulfillmentAdapter {

    private static final Logger log = LoggerFactory.getLogger(TierBoostAdapter.class);

    private final LedgerClient ledgerClient;

    public TierBoostAdapter(LedgerClient ledgerClient) {
        this.ledgerClient = ledgerClient;
    }

    @Override
    public RewardType supportedType() {
        return RewardType.TIER_BOOST;
    }

    @Override
    public FulfilmentResult fulfil(SagaContext ctx) {
        long qualifyingDelta = asLong(ctx.param("qualifyingDelta"));
        String sourceRef = "tierboost-" + ctx.sagaId();
        try {
            Long entryId = ledgerClient.grantQualifying(ctx.memberId(), ctx.programId(), qualifyingDelta, sourceRef);
            log.info("tier-boost fulfilment for saga {} (reward {}) -> granted qualifyingDelta {} as core entry {}",
                    ctx.sagaId(), ctx.rewardId(), qualifyingDelta, entryId);
            return FulfilmentResult.success(String.valueOf(entryId));
        } catch (RuntimeException e) {
            log.warn("tier-boost qualifying grant failed for saga {}: {}", ctx.sagaId(), e.toString());
            return FulfilmentResult.failure("core grant error: " + e.getMessage());
        }
    }

    private static long asLong(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        throw new IllegalArgumentException("tier-boost reward requires a numeric 'qualifyingDelta' fulfillment param");
    }
}
