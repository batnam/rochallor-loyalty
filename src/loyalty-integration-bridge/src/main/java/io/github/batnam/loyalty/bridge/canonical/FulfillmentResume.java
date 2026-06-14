package io.github.batnam.loyalty.bridge.canonical;

import java.time.Instant;
import java.util.Map;

/**
 * Canonical event: {@code loyalty.fulfillment.resume.v1}. Job-scoped — keyed on
 * {@code externalRef} (matches {@code redemption_saga.job_handle}); {@code loyalty-redemption} resumes
 * the saga. Not customer-scoped.
 *
 * <p>Field shape mirrors {@code loyalty-redemption}'s consumer view of this canonical event
 * ({@code ResumeEvent}): the correlation key is {@code externalRef}, the partner result is a
 * normalised {@code outcome} ({@code SUCCESS}/{@code FAILURE}), and the issued voucher code travels
 * in {@code payload}.
 */
public record FulfillmentResume(
        String eventId,
        String eventType,
        Instant occurredAt,
        Integer schemaVersion,
        String externalRef,
        String outcome,
        Map<String, Object> payload
) {
}
