package io.github.batnam.loyalty.redemption.fulfil.adapter;

import io.github.batnam.loyalty.redemption.fulfil.FulfilmentResult;
import io.github.batnam.loyalty.redemption.fulfil.FulfillmentAdapter;
import io.github.batnam.loyalty.redemption.fulfil.RewardType;
import io.github.batnam.loyalty.redemption.fulfil.SagaContext;
import io.github.batnam.loyalty.redemption.fulfil.client.PaymentHubClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Synchronous Cashback adapter (L3 §3.2 happy path): disburses {@code amount}/{@code currency} from the
 * Reward's fulfillment params to the customer's chosen CASA via the Payment Hub, in-process. The Hub is a
 * Host Bank capability, so it is addressed by {@code customerId} (CIF) and {@code accountNumber} (the
 * target CASA the customer picked on the Mobile App) — never Loyalty's internal {@code memberId}. Returns
 * SUCCESS with the Hub disbursement ref, or FAILURE if the Hub call fails (the Saga then releases the
 * reservation).
 */
@Component
public class CashbackAdapter implements FulfillmentAdapter {

    private static final Logger log = LoggerFactory.getLogger(CashbackAdapter.class);

    private final PaymentHubClient paymentHub;

    public CashbackAdapter(PaymentHubClient paymentHub) {
        this.paymentHub = paymentHub;
    }

    @Override
    public RewardType supportedType() {
        return RewardType.CASHBACK;
    }

    @Override
    public FulfilmentResult fulfil(SagaContext ctx) {
        String accountNumber = ctx.accountNumber();
        if (accountNumber == null || accountNumber.isBlank()) {
            return FulfilmentResult.failure("cashback requires a target accountNumber (CASA) — none supplied");
        }
        long amount = asLong(ctx.param("amount"));
        String currency = ctx.param("currency") == null ? "VND" : ctx.param("currency").toString();
        try {
            String ref = paymentHub.disburse(ctx.customerId(), accountNumber, amount, currency);
            return FulfilmentResult.success(ref);
        } catch (RuntimeException e) {
            log.warn("cashback disbursement failed for saga {}: {}", ctx.sagaId(), e.toString());
            return FulfilmentResult.failure("payment hub error: " + e.getMessage());
        }
    }

    private static long asLong(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        throw new IllegalArgumentException("cashback reward requires a numeric 'amount' fulfillment param");
    }
}
