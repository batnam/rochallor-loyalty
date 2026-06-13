package io.github.batnam.loyalty.core.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.batnam.loyalty.core.config.CoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Transactional-outbox relay (L3 §3.2, component 12). Two responsibilities:
 * <ul>
 *   <li>{@link #enqueue} — called <b>in the business transaction</b> to stage an event row.</li>
 *   <li>{@link #drain} — a {@code @Scheduled} tick that publishes PENDING rows to MSK and marks them
 *       SENT in a <b>separate</b> transaction (retryable; at-least-once, dedup downstream by eventId).</li>
 * </ul>
 * Single writer of the {@code outbox} table.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRepository outbox;
    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper mapper;
    private final int batchSize;

    public OutboxRelay(OutboxRepository outbox,
                       KafkaTemplate<String, String> kafka,
                       ObjectMapper mapper,
                       CoreProperties props) {
        this.outbox = outbox;
        this.kafka = kafka;
        this.mapper = mapper;
        this.batchSize = props.outbox().relayBatchSize();
    }

    /** Stage an event. MUST be called inside the caller's business transaction. */
    public void enqueue(String aggregateType, String eventType, String topic,
                        String partitionKey, Object payload) {
        String json;
        try {
            json = mapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize outbox payload for " + eventType, e);
        }
        outbox.save(OutboxEntry.pending(aggregateType, eventType, topic, partitionKey, json));
    }

    /** Publish PENDING rows to MSK. Runs in its own transaction, decoupled from business writes. */
    @Scheduled(fixedDelayString = "PT1S")
    @Transactional
    public void drain() {
        List<OutboxEntry> batch = outbox.findByStatusOrderByCreatedAtAsc(
                OutboxEntry.Status.PENDING, Limit.of(batchSize));
        for (OutboxEntry e : batch) {
            kafka.send(e.getTopic(), e.getPartitionKey(), e.getPayload());
            e.markSent();
            log.debug("relayed outbox id={} eventType={} topic={}", e.getId(), e.getEventType(), e.getTopic());
        }
    }
}
