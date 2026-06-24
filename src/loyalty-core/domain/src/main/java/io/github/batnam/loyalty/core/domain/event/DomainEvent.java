package io.github.batnam.loyalty.core.domain.event;

import java.time.Instant;

/**
 * A business fact recorded by an aggregate during a transaction. The aggregate only <i>records</i>
 * these (CONTEXT.md "Domain Event"); it never publishes them. The {@code :infra} outbox adapter
 * drains recorded events into the transactional outbox in the same transaction as the state change,
 * keeping Kafka and {@code jakarta.*} off the domain classpath.
 *
 * <p>The common accessors let the adapter drain any ledger-posting event generically — it stamps the
 * persisted {@code entryId} (which the domain does not know) onto {@link #eventName()}.
 */
public sealed interface DomainEvent
        permits PointsEarned, PointsRedeemed, PointsExpired, PointsAdjusted, PointsReversed {

    /** The canonical event name, e.g. {@code PointsEarned} → topic event {@code loyalty.ledger.PointsEarned}. */
    String eventName();

    long memberId();
    long programId();
    long qualifyingDelta();
    long redeemableDelta();
    String sourceRef();
    Instant occurredAt();
}
