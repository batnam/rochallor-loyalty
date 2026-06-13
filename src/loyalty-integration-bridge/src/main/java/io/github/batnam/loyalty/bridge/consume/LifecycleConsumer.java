package io.github.batnam.loyalty.bridge.consume;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.batnam.loyalty.bridge.canonical.MemberLifecycle;
import io.github.batnam.loyalty.bridge.config.BridgeTopics;
import io.github.batnam.loyalty.bridge.ingress.CustomerLifecycleIngress;
import io.github.batnam.loyalty.bridge.translate.LifecycleTranslator;
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
 * Consume → validate → translate → produce for {@code loyalty.ingress.customer_lifecycle.v1} →
 * {@code loyalty.member.lifecycle.v1} (ADR-0025). Customer-scoped; {@code loyalty-core} consumes it.
 */
@Component
public class LifecycleConsumer {

    private static final Logger log = LoggerFactory.getLogger(LifecycleConsumer.class);
    private static final String[] PROPAGATED_HEADERS = {"traceparent", "source"};

    private final IngressSchemaValidator validator;
    private final ObjectMapper mapper;
    private final KafkaTemplate<String, String> kafka;
    private final BridgeTopics topics;

    public LifecycleConsumer(IngressSchemaValidator validator,
                             ObjectMapper mapper,
                             KafkaTemplate<String, String> kafka,
                             BridgeTopics topics) {
        this.validator = validator;
        this.mapper = mapper;
        this.kafka = kafka;
        this.topics = topics;
    }

    @KafkaListener(topics = "${bridge.topics.ingress-customer-lifecycle}")
    public void onLifecycle(ConsumerRecord<String, String> record) throws Exception {
        JsonNode node;
        try {
            node = mapper.readTree(record.value());
        } catch (Exception parseError) {
            toDlq(record, "unparseable JSON: " + parseError.getMessage());
            return;
        }

        Set<ValidationMessage> errors = validator.validateLifecycle(node);
        if (!errors.isEmpty()) {
            toDlq(record, "schema validation failed: " + errors);
            return;
        }

        CustomerLifecycleIngress ingress = mapper.treeToValue(node, CustomerLifecycleIngress.class);
        MemberLifecycle lifecycle = LifecycleTranslator.translate(ingress);

        ProducerRecord<String, String> out = new ProducerRecord<>(
                topics.memberLifecycle(),
                String.valueOf(lifecycle.customerId()),
                mapper.writeValueAsString(lifecycle));
        propagateHeaders(record, out);
        kafka.send(out);
        log.debug("translated lifecycle eventId={} type={}", lifecycle.eventId(), lifecycle.lifecycleType());
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
        log.warn("routing lifecycle to DLQ: {} (key={})", reason, record.key());
        ProducerRecord<String, String> dlq =
                new ProducerRecord<>(topics.lifecycleDlq(), record.key(), record.value());
        dlq.headers().add("dlq-reason", reason.getBytes(StandardCharsets.UTF_8));
        kafka.send(dlq);
    }
}
