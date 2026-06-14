package io.github.batnam.loyalty.redemption.fulfil;

import java.util.Map;

/**
 * The partner's asynchronous verdict carried on {@code loyalty.fulfillment.resume.v1} (L3 §3.3): did the
 * provisioning succeed, the {@code externalRef} it correlates to, and any extra payload (e.g. the issued
 * voucher code). Fed to {@link FulfillmentAdapter#resume(String, PartnerOutcome)}.
 */
public record PartnerOutcome(boolean success, String externalRef, Map<String, Object> payload) {
}
