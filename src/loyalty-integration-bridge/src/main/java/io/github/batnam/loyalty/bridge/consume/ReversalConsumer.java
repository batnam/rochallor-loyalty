package io.github.batnam.loyalty.bridge.consume;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.batnam.loyalty.bridge.canonical.ReversalEvent;
import io.github.batnam.loyalty.bridge.config.BridgeTopics;
import io.github.batnam.loyalty.bridge.ingress.ReversalIngress;
import io.github.batnam.loyalty.bridge.translate.ReversalTranslator;
import io.github.batnam.loyalty.bridge.validate.IngressSchemaValidator;
import com.networknt.schema.ValidationMessage;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Consume → validate → translate → produce for {@code loyalty.ingress.reversal.v1} →
 * {@code loyalty.payment.reversed.v1}. Clawback itself is core-driven:
 * the Bridge only translates; {@code loyalty-core} reverses entries by {@code source_ref}.
 */
@Component
public class ReversalConsumer {

    private static final Logger log = LoggerFactory.getLogger(ReversalConsumer.class);
    private static final String[] PROPAGATED_HEADERS = {"traceparent", "source"};

    private final IngressSchemaValidator validator;
    private final ObjectMapper mapper;
    private final KafkaTemplate<String, String> kafka;
    private final BridgeTopics topics;

    public ReversalConsumer(IngressSchemaValidator validator,
                            ObjectMapper mapper,
                            KafkaTemplate<String, String> kafka,
                            BridgeTopics topics) {
        this.validator = validator;
        this.mapper = mapper;
        this.kafka = kafka;
        this.topics = topics;
    }

    @KafkaListener(topics = "${bridge.topics.ingress-reversal}")
    public void onReversal(ConsumerRecord<String, String> record) throws Exception {
        JsonNode node;
        try {
            node = mapper.readTree(record.value());
        } catch (Exception parseError) {
            toDlq(record, "unparseable JSON: " + parseError.getMessage());
            return;
        }

        Set<ValidationMessage> errors = validator.validateReversal(node);
        if (!errors.isEmpty()) {
            toDlq(record, "schema validation failed: " + errors);
            return;
        }

        ReversalIngress ingress = mapper.treeToValue(node, ReversalIngress.class);
        ReversalEvent reversal = ReversalTranslator.translate(ingress);

        ProducerRecord<String, String> out = new ProducerRecord<>(
                topics.paymentReversed(),
                String.valueOf(reversal.customerId()),
                mapper.writeValueAsString(reversal));
        propagateHeaders(record, out);
        kafka.send(out);
        log.debug("translated reversal eventId={} originalEventId={}",
                reversal.eventId(), reversal.originalEventId());
    }

    private void propagateHeaders(ConsumerRecord<String, String> in, ProducerRecord<String, String> out) {
        for (String name : PROPAGATED_HEADERS) {
            Header h = in.headers().lastHeader(name);
            if (h != null) {
                out.headers().add(name, h.value());
            }
        }
    }

    private void toDlq(ConsumerRecord<String, String> record, String reason) {
        log.warn("routing reversal to DLQ: {} (key={})", reason, record.key());
        ProducerRecord<String, String> dlq =
                new ProducerRecord<>(topics.reversalDlq(), record.key(), record.value());
        dlq.headers().add("dlq-reason", reason.getBytes(StandardCharsets.UTF_8));
        kafka.send(dlq);
    }
}
