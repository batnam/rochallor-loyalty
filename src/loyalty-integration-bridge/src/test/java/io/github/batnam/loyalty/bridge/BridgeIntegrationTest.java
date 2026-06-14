package io.github.batnam.loyalty.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.testcontainers.DockerClientFactory.instance;

/**
 * End-to-end ingress-gateway test against a real Kafka broker (Testcontainers): produce a
 * Loyalty-authored {@code loyalty.ingress.*} event, assert the canonical {@code loyalty.*} output —
 * or, for an invalid event, assert it lands in the per-channel DLQ. Skipped when Docker is absent.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class BridgeIntegrationTest {

    @Container
    static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

    @DynamicPropertySource
    static void kafkaProps(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @BeforeAll
    static void requireDocker() {
        assumeTrue(instance().isDockerAvailable(), "Docker not available — skipping Testcontainers IT");
    }

    @Autowired
    KafkaTemplate<String, String> template;
    @Autowired
    ObjectMapper mapper;

    @Test
    void cardSpendIngressBecomesCanonicalEarnEvent() throws Exception {
        String body = """
                {"eventId":"it:cs:1","customerId":100990001,"occurredAt":"2026-05-29T10:30:00Z",
                 "amount":42.50,"currency":"USD","mcc":"5411","schemaVersion":1}""";
        template.send("loyalty.ingress.card_spend.v1", "100990001", body);

        JsonNode earn = awaitEventWithId("loyalty.earn.translated.v1", "it:cs:1");
        assertThat(earn.path("source").asText()).isEqualTo("CARD_SPEND");
        assertThat(earn.path("customerId").asLong()).isEqualTo(100990001L);
        assertThat(earn.path("payload").path("currency").asText()).isEqualTo("USD");
        assertThat(earn.has("memberId")).isFalse();   // customer-scoped
        assertThat(earn.has("programId")).isFalse();
    }

    @Test
    void paymentP2pRoutesToFundTransferAndPreservesPaymentType() throws Exception {
        String body = """
                {"eventId":"it:pay:1","customerId":100990002,"occurredAt":"2026-05-29T11:00:00Z",
                 "amount":50.0,"currency":"USD","paymentType":"P2P_TRANSFER","schemaVersion":1}""";
        template.send("loyalty.ingress.payment.v1", "100990002", body);

        JsonNode earn = awaitEventWithId("loyalty.earn.translated.v1", "it:pay:1");
        assertThat(earn.path("source").asText()).isEqualTo("FUND_TRANSFER");                  // routed
        assertThat(earn.path("payload").path("paymentType").asText()).isEqualTo("P2P_TRANSFER"); // preserved for DSL
    }

    @Test
    void schemaInvalidCardSpendGoesToDlq() throws Exception {
        // missing required amount + currency → fails JSON Schema → per-channel DLQ
        String body = """
                {"eventId":"it:cs:bad","customerId":1,"occurredAt":"2026-05-29T10:30:00Z","schemaVersion":1}""";
        template.send("loyalty.ingress.card_spend.v1", "1", body);

        ConsumerRecord<String, String> dead =
                awaitRecordContaining("loyalty.ingress.card_spend.v1.dlq", "it:cs:bad");
        assertThat(dead.headers().lastHeader("dlq-reason")).isNotNull();
    }

    // --- helpers -------------------------------------------------------------

    private JsonNode awaitEventWithId(String topic, String eventId) throws Exception {
        ConsumerRecord<String, String> rec = poll(topic, eventId);
        return mapper.readTree(rec.value());
    }

    private ConsumerRecord<String, String> awaitRecordContaining(String topic, String marker) {
        return poll(topic, marker);
    }

    private ConsumerRecord<String, String> poll(String topic, String marker) {
        try (Consumer<String, String> consumer = newConsumer(topic)) {
            long deadline = System.currentTimeMillis() + 25_000;
            while (System.currentTimeMillis() < deadline) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> r : records) {
                    if (r.value() != null && r.value().contains(marker)) {
                        return r;
                    }
                }
            }
            throw new AssertionError("no record containing '" + marker + "' on " + topic);
        }
    }

    private Consumer<String, String> newConsumer(String topic) {
        Map<String, Object> props =
                KafkaTestUtils.consumerProps(KAFKA.getBootstrapServers(), "it-" + topic, "true");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        Consumer<String, String> consumer = new DefaultKafkaConsumerFactory<>(
                props, new StringDeserializer(), new StringDeserializer()).createConsumer();
        consumer.subscribe(List.of(topic));
        return consumer;
    }
}
