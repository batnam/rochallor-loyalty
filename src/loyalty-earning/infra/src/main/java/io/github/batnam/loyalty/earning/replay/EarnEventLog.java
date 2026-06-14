package io.github.batnam.loyalty.earning.replay;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * Replay-store row (L3 §3.3): every consumed EarnEvent persisted post-Bridge-translation so the
 * dry-run evaluator can replay a historical window through the same DSL Interpreter the hot path
 * uses, with no side effects. {@code payload} is the raw translated JSON.
 */
@Entity
@Table(name = "earn_event_log")
public class EarnEventLog {

    @Id
    @Column(name = "event_id", updatable = false)
    private String eventId;

    @Column(name = "source", nullable = false, updatable = false)
    private String source;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private Long customerId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, updatable = false)
    private String payload;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "received_at", nullable = false, updatable = false, insertable = false)
    private Instant receivedAt;

    protected EarnEventLog() {
    }

    public static EarnEventLog of(String eventId, String source, Long customerId,
                                  String payload, Instant occurredAt) {
        EarnEventLog e = new EarnEventLog();
        e.eventId = eventId;
        e.source = source;
        e.customerId = customerId;
        e.payload = payload;
        e.occurredAt = occurredAt;
        return e;
    }

    public String getEventId() { return eventId; }
    public String getSource() { return source; }
    public Long getCustomerId() { return customerId; }
    public String getPayload() { return payload; }
    public Instant getOccurredAt() { return occurredAt; }
    public Instant getReceivedAt() { return receivedAt; }
}
