package io.github.batnam.loyalty.core.consume;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.batnam.loyalty.core.reversal.ReversalService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code loyalty.payment.reversed.v1} produced by {@code loyalty-integration-bridge}. The
 * event carries the original earn event's id ({@code originalEventId}); the clawback reverses every
 * {@code Earned} entry that event produced. Reversal of an event that never earned is a silent no-op.
 */
@Component
public class PaymentReversedConsumer {

    private static final Logger log = LoggerFactory.getLogger(PaymentReversedConsumer.class);

    private final ObjectMapper mapper;
    private final ReversalService reversal;

    public PaymentReversedConsumer(ObjectMapper mapper, ReversalService reversal) {
        this.mapper = mapper;
        this.reversal = reversal;
    }

    @KafkaListener(topics = "${core.topics.payment-reversed}")
    public void onReversal(ConsumerRecord<String, String> record) throws Exception {
        JsonNode node = mapper.readTree(record.value());
        String originalEventId = node.path("originalEventId").asText(null);

        if (originalEventId == null || originalEventId.isBlank()) {
            log.warn("ignoring reversal with no originalEventId");
            return;
        }
        reversal.reversePaymentEvent(originalEventId);
    }
}
