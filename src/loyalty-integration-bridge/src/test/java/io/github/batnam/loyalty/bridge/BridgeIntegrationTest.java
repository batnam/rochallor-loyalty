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
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
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
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class BridgeIntegrationTest {

    private static final String WEBHOOK_SECRET = "it-voucher-secret";

    @Container
    static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

    @DynamicPropertySource
    static void kafkaProps(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        // Production threshold (500) is impractical for an IT; trip the velocity anomaly at 3.
        registry.add("bridge.velocity.max-earns-per-window", () -> 3);
        registry.add("bridge.voucher.hmac-secret", () -> WEBHOOK_SECRET);
    }

    @BeforeAll
    static void requireDocker() {
        assumeTrue(instance().isDockerAvailable(), "Docker not available — skipping Testcontainers IT");
    }

    @Autowired
    KafkaTemplate<String, String> template;
    @Autowired
    ObjectMapper mapper;
    @LocalServerPort
    int port;

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
    void termDepositIngressBecomesCanonicalEarnEvent() throws Exception {
        String body = """
                {"eventId":"it:td:1","customerId":100990003,"occurredAt":"2026-05-29T12:00:00Z",
                 "amount":10000.00,"currency":"VND","termMonths":12,"schemaVersion":1}""";
        template.send("loyalty.ingress.term_deposit.v1", "100990003", body);

        JsonNode earn = awaitEventWithId("loyalty.earn.translated.v1", "it:td:1");
        assertThat(earn.path("source").asText()).isEqualTo("TERM_DEPOSIT_OPENED");
        assertThat(earn.path("customerId").asLong()).isEqualTo(100990003L);
        assertThat(earn.path("payload").path("currency").asText()).isEqualTo("VND");
        assertThat(earn.path("payload").path("termMonths").asInt()).isEqualTo(12);
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
    void reversalIngressBecomesCanonicalPaymentReversedEvent() throws Exception {
        String body = """
                {"eventId":"it:rev:1","customerId":100990004,"occurredAt":"2026-05-29T13:00:00Z",
                 "originalEventId":"it:cs:1","reversalEventId":"it:rev:1:rb",
                 "amount":42.50,"currency":"USD","schemaVersion":1}""";
        template.send("loyalty.ingress.reversal.v1", "100990004", body);

        JsonNode reversed = awaitEventWithId("loyalty.payment.reversed.v1", "it:rev:1");
        assertThat(reversed.path("eventType").asText()).isEqualTo("loyalty.payment.reversed.v1");
        assertThat(reversed.path("customerId").asLong()).isEqualTo(100990004L);
        assertThat(reversed.path("originalEventId").asText()).isEqualTo("it:cs:1"); // core matches source_ref
        assertThat(reversed.path("reversalEventId").asText()).isEqualTo("it:rev:1:rb");
        assertThat(reversed.path("currency").asText()).isEqualTo("USD");
    }

    @Test
    void customerLifecycleIngressBecomesCanonicalMemberLifecycleEvent() throws Exception {
        String body = """
                {"eventId":"it:lc:1","customerId":100990005,"occurredAt":"2026-05-29T14:00:00Z",
                 "lifecycleType":"CUSTOMER_CLOSED","schemaVersion":1}""";
        template.send("loyalty.ingress.customer_lifecycle.v1", "100990005", body);

        JsonNode lifecycle = awaitEventWithId("loyalty.member.lifecycle.v1", "it:lc:1");
        assertThat(lifecycle.path("eventType").asText()).isEqualTo("loyalty.member.lifecycle.v1");
        assertThat(lifecycle.path("customerId").asLong()).isEqualTo(100990005L);
        assertThat(lifecycle.path("lifecycleType").asText()).isEqualTo("CUSTOMER_CLOSED"); // preserved
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

    @Test
    void velocitySpikeEmitsFraudAlertWhenEarnCountCrossesThreshold() throws Exception {
        // Threshold overridden to 3 (see kafkaProps). Drive 4 earns for one customer within the
        // window so the count crosses 3 → EARN_VELOCITY_SPIKE on loyalty.fraud.alert.v1.
        // occurredAt must sit inside the 30-day sliding window ending at Instant.now().
        long customerId = 100990777L;
        String occurredAt = Instant.now().minus(Duration.ofMinutes(5)).toString();
        for (int i = 1; i <= 4; i++) {
            String body = String.format("""
                    {"eventId":"it:vel:%d","customerId":%d,"occurredAt":"%s",
                     "amount":10.0,"currency":"USD","mcc":"5411","schemaVersion":1}""",
                    i, customerId, occurredAt);
            template.send("loyalty.ingress.card_spend.v1", String.valueOf(customerId), body);
        }

        JsonNode alert = awaitRecordByKey("loyalty.fraud.alert.v1", String.valueOf(customerId));
        assertThat(alert.path("eventType").asText()).isEqualTo("loyalty.fraud.alert.v1");
        assertThat(alert.path("customerId").asLong()).isEqualTo(customerId);
        assertThat(alert.path("anomalyType").asText()).isEqualTo("EARN_VELOCITY_SPIKE");
        assertThat(alert.path("threshold").asDouble()).isEqualTo(3.0);
        assertThat(alert.path("observedRate").asDouble()).isGreaterThan(3.0);
    }

    @Test
    void voucherWebhookWithValidSignatureEmitsFulfillmentResume() throws Exception {
        String jobHandle = "job-it-resume-1";
        String rawBody = """
                {"jobHandle":"%s","status":"READY","voucherCode":"VC-XYZ","partnerRef":"PR-1"}"""
                .formatted(jobHandle);
        long ts = Instant.now().getEpochSecond();

        HttpResponse<Void> resp = postWebhook(rawBody, hmacHex(ts, rawBody), ts);
        assertThat(resp.statusCode()).isEqualTo(202); // ACCEPTED

        JsonNode resume = awaitEventWithId(
                "loyalty.fulfillment.resume.v1", "voucher-resume:" + jobHandle + ":READY");
        assertThat(resume.path("eventType").asText()).isEqualTo("loyalty.fulfillment.resume.v1");
        assertThat(resume.path("externalRef").asText()).isEqualTo(jobHandle); // carries jobHandle
        assertThat(resume.path("outcome").asText()).isEqualTo("SUCCESS");      // READY → SUCCESS
        assertThat(resume.path("payload").path("voucherCode").asText()).isEqualTo("VC-XYZ");
    }

    @Test
    void voucherWebhookWithBadSignatureIsRejectedAndEmitsNoResume() throws Exception {
        String jobHandle = "job-it-replay-1";
        String rawBody = """
                {"jobHandle":"%s","status":"READY","voucherCode":"VC-BAD","partnerRef":"PR-2"}"""
                .formatted(jobHandle);
        long ts = Instant.now().getEpochSecond();

        // DD-2: tampered signature → 401, no resume event.
        HttpResponse<Void> bad = postWebhook(rawBody, "deadbeef", ts);
        assertThat(bad.statusCode()).isEqualTo(401); // UNAUTHORIZED

        // DD-2: stale timestamp (outside tolerance) with an otherwise-correct signature → 401.
        long staleTs = Instant.now().getEpochSecond() - 10_000;
        HttpResponse<Void> stale = postWebhook(rawBody, hmacHex(staleTs, rawBody), staleTs);
        assertThat(stale.statusCode()).isEqualTo(401); // UNAUTHORIZED

        assertNoRecordWithin(
                "loyalty.fulfillment.resume.v1", "voucher-resume:" + jobHandle + ":READY");
    }

    // --- helpers -------------------------------------------------------------

    private HttpResponse<Void> postWebhook(String rawBody, String signatureHex, long timestamp)
            throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/webhooks/voucher"))
                .header("Content-Type", "application/json")
                .header("X-Signature", signatureHex)
                .header("X-Timestamp", String.valueOf(timestamp))
                .POST(HttpRequest.BodyPublishers.ofString(rawBody))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.discarding());
    }

    private static String hmacHex(long timestamp, String rawBody) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(WEBHOOK_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(
                mac.doFinal((timestamp + "." + rawBody).getBytes(StandardCharsets.UTF_8)));
    }

    private JsonNode awaitRecordByKey(String topic, String key) throws Exception {
        try (Consumer<String, String> consumer = newConsumer(topic)) {
            long deadline = System.currentTimeMillis() + 25_000;
            while (System.currentTimeMillis() < deadline) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> r : records) {
                    if (key.equals(r.key())) {
                        return mapper.readTree(r.value());
                    }
                }
            }
            throw new AssertionError("no record with key '" + key + "' on " + topic);
        }
    }

    private void assertNoRecordWithin(String topic, String marker) {
        try (Consumer<String, String> consumer = newConsumer(topic)) {
            long deadline = System.currentTimeMillis() + 5_000;
            while (System.currentTimeMillis() < deadline) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> r : records) {
                    if (r.value() != null && r.value().contains(marker)) {
                        throw new AssertionError("unexpected record containing '" + marker + "' on " + topic);
                    }
                }
            }
        }
    }


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
