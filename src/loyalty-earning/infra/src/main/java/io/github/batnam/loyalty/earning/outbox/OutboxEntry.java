package io.github.batnam.loyalty.earning.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Transactional-outbox staging row (L3 §3.2). Inserted in the same transaction as the business write
 * (the idempotency-key write that confirms the event was processed); drained to MSK by the Outbox
 * Relay in a separate, retryable transaction — at-least-once delivery, dedup downstream by eventId.
 */
@Entity
@Table(name = "outbox")
public class OutboxEntry {

    public enum Status { PENDING, SENT }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "aggregate_type", nullable = false, updatable = false)
    private String aggregateType;

    @Column(name = "event_type", nullable = false, updatable = false)
    private String eventType;

    @Column(name = "topic", nullable = false, updatable = false)
    private String topic;

    @Column(name = "partition_key", nullable = false, updatable = false)
    private String partitionKey;

    @Column(name = "payload", nullable = false, updatable = false)
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private Status status = Status.PENDING;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    protected OutboxEntry() {
    }

    public static OutboxEntry pending(String aggregateType, String eventType, String topic,
                                      String partitionKey, String payload) {
        OutboxEntry e = new OutboxEntry();
        e.aggregateType = aggregateType;
        e.eventType = eventType;
        e.topic = topic;
        e.partitionKey = partitionKey;
        e.payload = payload;
        e.status = Status.PENDING;
        return e;
    }

    public void markSent() {
        this.status = Status.SENT;
        this.sentAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getAggregateType() { return aggregateType; }
    public String getEventType() { return eventType; }
    public String getTopic() { return topic; }
    public String getPartitionKey() { return partitionKey; }
    public String getPayload() { return payload; }
    public Status getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getSentAt() { return sentAt; }
}
