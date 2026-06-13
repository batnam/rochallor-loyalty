package io.github.batnam.loyalty.bridge.consume;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.batnam.loyalty.bridge.canonical.EarnEvent;
import io.github.batnam.loyalty.bridge.config.BridgeTopics;
import io.github.batnam.loyalty.bridge.config.PaymentMapping;
import io.github.batnam.loyalty.bridge.ingress.PaymentIngress;
import io.github.batnam.loyalty.bridge.translate.PaymentTranslator;
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
 * Consume → validate → stamp → produce for {@code loyalty.ingress.payment.v1} (ADR-0025).
 * Routes canonical {@code paymentType} to an {@code earn_source} via the bank-uniform
 * {@link PaymentMapping}; an unmapped (but contract-valid) paymentType emits the inactive
 * {@code PAYMENT_COMPLETED} fallback and is alerted — the {@code EMIT_FALLBACK_AND_ALERT} policy
 * (ADR-0021). Schema-invalid messages go to the per-channel DLQ.
 */
@Component
public class PaymentConsumer {

    private static final Logger log = LoggerFactory.getLogger(PaymentConsumer.class);
    private static final String[] PROPAGATED_HEADERS = {"traceparent", "source"};

    private final IngressSchemaValidator validator;
    private final PaymentMapping mapping;
    private final ObjectMapper mapper;
    private final KafkaTemplate<String, String> kafka;
    private final BridgeTopics topics;

    public PaymentConsumer(IngressSchemaValidator validator,
                           PaymentMapping mapping,
                           ObjectMapper mapper,
                           KafkaTemplate<String, String> kafka,
                           BridgeTopics topics) {
        this.validator = validator;
        this.mapping = mapping;
        this.mapper = mapper;
        this.kafka = kafka;
        this.topics = topics;
    }

    @KafkaListener(topics = "${bridge.topics.ingress-payment}")
    public void onPayment(ConsumerRecord<String, String> record) throws Exception {
        JsonNode node;
        try {
            node = mapper.readTree(record.value());
        } catch (Exception parseError) {
            toDlq(record, "unparseable JSON: " + parseError.getMessage());
            return;
        }

        Set<ValidationMessage> errors = validator.validatePayment(node);
        if (!errors.isEmpty()) {
            toDlq(record, "schema validation failed: " + errors);
            return;
        }

        PaymentIngress ingress = mapper.treeToValue(node, PaymentIngress.class);
        if (!mapping.isMapped(ingress.paymentType())) {
            // EMIT_FALLBACK_AND_ALERT (ADR-0021): contract-valid but unrouted → inactive fallback source.
            log.warn("unmapped paymentType={} → fallback earn_source={} (eventId={})",
                    ingress.paymentType(), mapping.fallbackEarnSource(), ingress.eventId());
        }

        EarnEvent earn = PaymentTranslator.translate(ingress, mapping);

        ProducerRecord<String, String> out = new ProducerRecord<>(
                topics.earnTranslated(),
                String.valueOf(earn.customerId()),
                mapper.writeValueAsString(earn));
        propagateHeaders(record, out);
        kafka.send(out);
        log.debug("translated payment eventId={} paymentType={} → source={}",
                earn.eventId(), ingress.paymentType(), earn.source());
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
        log.warn("routing payment to DLQ: {} (key={})", reason, record.key());
        ProducerRecord<String, String> dlq =
                new ProducerRecord<>(topics.paymentDlq(), record.key(), record.value());
        dlq.headers().add("dlq-reason", reason.getBytes(StandardCharsets.UTF_8));
        kafka.send(dlq);
    }
}
