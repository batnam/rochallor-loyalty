package io.github.batnam.loyalty.bridge.consume;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.batnam.loyalty.bridge.canonical.EarnEvent;
import io.github.batnam.loyalty.bridge.config.BridgeTopics;
import io.github.batnam.loyalty.bridge.ingress.TermDepositIngress;
import io.github.batnam.loyalty.bridge.translate.TermDepositTranslator;
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
 * The consume → validate → stamp → produce loop for {@code loyalty.ingress.term_deposit.v1}
 *. Schema-invalid or unparseable messages go to a per-channel DLQ and are NOT forwarded
 * downstream; valid ones become {@code loyalty.earn.translated.v1}. {@code traceparent} and
 * {@code source} headers are propagated across the seam so one distributed trace spans bank →
 * Bridge → earning.
 */
@Component
public class TermDepositConsumer {

    private static final Logger log = LoggerFactory.getLogger(TermDepositConsumer.class);
    private static final String[] PROPAGATED_HEADERS = {"traceparent", "source"};

    private final IngressSchemaValidator validator;
    private final ObjectMapper mapper;
    private final KafkaTemplate<String, String> kafka;
    private final BridgeTopics topics;

    public TermDepositConsumer(IngressSchemaValidator validator,
                               ObjectMapper mapper,
                               KafkaTemplate<String, String> kafka,
                               BridgeTopics topics) {
        this.validator = validator;
        this.mapper = mapper;
        this.kafka = kafka;
        this.topics = topics;
    }

    @KafkaListener(topics = "${bridge.topics.ingress-term-deposit}")
    public void onTermDeposit(ConsumerRecord<String, String> record) throws Exception {
        JsonNode node;
        try {
            node = mapper.readTree(record.value());
        } catch (Exception parseError) {
            toDlq(record, "unparseable JSON: " + parseError.getMessage());
            return;
        }

        Set<ValidationMessage> errors = validator.validateTermDeposit(node);
        if (!errors.isEmpty()) {
            toDlq(record, "schema validation failed: " + errors);
            return;
        }

        TermDepositIngress ingress = mapper.treeToValue(node, TermDepositIngress.class);
        EarnEvent earn = TermDepositTranslator.translate(ingress);

        ProducerRecord<String, String> out = new ProducerRecord<>(
                topics.earnTranslated(),
                String.valueOf(earn.customerId()),   // partition key = customerId
                mapper.writeValueAsString(earn));
        propagateHeaders(record, out);
        kafka.send(out);
        log.debug("translated term_deposit eventId={} customerId={}", earn.eventId(), earn.customerId());
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
        log.warn("routing term_deposit to DLQ: {} (key={})", reason, record.key());
        ProducerRecord<String, String> dlq =
                new ProducerRecord<>(topics.termDepositDlq(), record.key(), record.value());
        dlq.headers().add("dlq-reason", reason.getBytes(StandardCharsets.UTF_8));
        kafka.send(dlq);
    }
}
