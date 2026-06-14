package io.github.batnam.loyalty.core.event;

import java.time.Instant;

/**
 * Canonical {@code loyalty.member.*} event envelope produced by core via the Outbox Relay
 * (e.g. {@code MemberClosed}, {@code MemberOptedIn}, {@code TierChanged}). Consumed by the Host Bank
 * Platform's notification-service and other downstream subscribers.
 */
public record MemberEvent(
        String eventId,
        String eventType,
        Instant occurredAt,
        Integer schemaVersion,
        Long memberId,
        Long programId,
        Long customerId,
        String status,
        String detail
) {
    public static MemberEvent of(String eventType, Long memberId, Long programId, Long customerId,
                                 String status, String detail, Instant occurredAt) {
        return new MemberEvent(eventType + "-" + memberId, eventType, occurredAt, 1,
                memberId, programId, customerId, status, detail);
    }
}
