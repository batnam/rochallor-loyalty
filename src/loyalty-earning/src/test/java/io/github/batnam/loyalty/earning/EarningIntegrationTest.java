package io.github.batnam.loyalty.earning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.github.batnam.loyalty.earning.caps.CapRepository;
import io.github.batnam.loyalty.earning.consume.EarnEvent;
import io.github.batnam.loyalty.earning.dsl.RuleDsl;
import io.github.batnam.loyalty.earning.engine.IdempotencyRepository;
import io.github.batnam.loyalty.earning.engine.RuleEngine;
import io.github.batnam.loyalty.earning.member.MemberRef;
import io.github.batnam.loyalty.earning.outbox.OutboxEntry;
import io.github.batnam.loyalty.earning.outbox.OutboxRepository;
import io.github.batnam.loyalty.earning.replay.EarnEventLogRepository;
import io.github.batnam.loyalty.earning.source.EarnSourceRegistry;
import io.github.batnam.loyalty.earning.source.EarnSourceRepository;
import io.github.batnam.loyalty.earning.source.EarningRule;
import io.github.batnam.loyalty.earning.source.RuleRepository;
import io.github.batnam.loyalty.earning.source.RuleStatus;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.web.client.RestClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.testcontainers.DockerClientFactory.instance;

/**
 * End-to-end loyalty-earning test against real Postgres + Kafka (Testcontainers) with loyalty-core's
 * Ledger API + member lookup stubbed by WireMock. Exercises the Rule Engine write path (DSL → cap →
 * ledger → outbox), idempotent replay, multi-rule sum, cap exhaustion, the atomic conditional cap
 * decrement, rule CRUD + DSL validation over REST, the Kafka consumer end-to-end, and side-effect-free
 * dry-run. Skipped when Docker is absent (mirrors loyalty-core's IT).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class EarningIntegrationTest {

    private static final long PROGRAM = 1L;
    private static final long MEMBER = 42L;
    private static final long CUSTOMER = 999L;
    private static final MemberRef MEMBER_REF = new MemberRef(MEMBER, PROGRAM, "ACTIVE", java.math.BigDecimal.ONE);

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    @Container
    static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

    static final WireMockServer CORE = new WireMockServer(options().dynamicPort());

    static {
        CORE.start();   // before @DynamicPropertySource so earning.core.base-url can resolve
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("earning.core.base-url", CORE::baseUrl);
    }

    @BeforeAll
    static void requireDocker() {
        assumeTrue(instance().isDockerAvailable(), "Docker not available — skipping Testcontainers IT");
    }

    @Autowired RuleEngine engine;
    @Autowired EarnSourceRegistry registry;
    @Autowired EarnSourceRepository earnSources;
    @Autowired RuleRepository ruleRepo;
    @Autowired OutboxRepository outbox;
    @Autowired IdempotencyRepository idempotency;
    @Autowired CapRepository caps;
    @Autowired EarnEventLogRepository replayStore;
    @Autowired KafkaTemplate<String, String> template;
    @Autowired ObjectMapper mapper;
    @Autowired Environment env;
    @Autowired io.github.batnam.loyalty.earning.caps.CapService capService;
    @Autowired io.github.batnam.loyalty.earning.caps.CapPurgeJob capPurgeJob;
    @Autowired javax.sql.DataSource dataSource;

    private RestClient http;

    /** POST {@code body} as JSON; return [status, responseBody] without throwing on 4xx/5xx. */
    private int[] statusOf(String uri, Object body, StringBuilder out) {
        return http.post().uri(uri)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(body).exchange((request, response) -> {
            out.append(new String(response.getBody().readAllBytes()));
            return new int[]{response.getStatusCode().value()};
        });
    }

    private static final String RATE_RULE = """
        {"dslVersion":1,"earnSource":"CARD_SPEND",
         "rows":[{"when":{"amount":"*"},"earn":{"type":"RATE","perAmount":10000,"points":1}}]}""";

    @BeforeEach
    void setUp() {
        // Isolation: engine.process commits, so clear mutable state between tests (audit log is
        // insert-only and accumulates; earn_source is seeded — both left intact).
        outbox.deleteAll();
        idempotency.deleteAll();
        caps.deleteAll();
        replayStore.deleteAll();
        ruleRepo.deleteAll();
        // earn_source_cap is seeded empty; clear any row a prior test inserted so caps stay opt-in.
        try (java.sql.Connection c = dataSource.getConnection();
             var st = c.createStatement()) {
            st.executeUpdate("DELETE FROM earn_source_cap");
        } catch (java.sql.SQLException e) {
            throw new RuntimeException(e);
        }

        http = RestClient.create("http://localhost:" + env.getProperty("local.server.port"));

        CORE.resetAll();
        CORE.stubFor(get(urlPathEqualTo("/members/lookup"))
                .withQueryParam("customerId", equalTo(String.valueOf(CUSTOMER)))
                .willReturn(okJson("""
                    {"memberId":%d,"programId":%d,"status":"ACTIVE"}""".formatted(MEMBER, PROGRAM))));
        CORE.stubFor(get(urlPathEqualTo("/members/lookup"))
                .withQueryParam("customerId", equalTo("12345"))   // an unknown customer
                .willReturn(aResponse().withStatus(404)));
        CORE.stubFor(post(urlEqualTo("/ledger/earn")).willReturn(okJson("""
            {"entryId":777,"memberId":%d,"programId":%d,"entryType":"Earned",
             "qualifyingDelta":0,"redeemableDelta":0,"sourceRef":"x","createdAt":"2026-05-30T10:00:00Z"}"""
                .formatted(MEMBER, PROGRAM)).withStatus(201)));
    }

    // --- helpers -------------------------------------------------------------

    private long cardSpendSourceId() {
        return earnSources.findByEarnSourceCode("CARD_SPEND").orElseThrow().getEarnSourceId();
    }

    private long activeRule(String dslJson) {
        try {
            EarningRule r = registry.createRule("op", PROGRAM, cardSpendSourceId(),
                    mapper.readTree(dslJson), null, null, null);
            registry.transitionStatus("supervisor", r.getRuleId(), RuleStatus.ACTIVE, "BEP-APV-1");
            return r.getRuleId();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private EarnEvent cardSpend(String eventId, int amount) {
        return new EarnEvent(eventId, "loyalty.earn.translated.v1", Instant.parse("2026-05-30T10:00:00Z"),
                1, CUSTOMER, "CARD_SPEND", Map.of("amount", amount, "currency", "USD"));
    }

    private int ledgerCalls() {
        return CORE.findAll(postRequestedFor(urlEqualTo("/ledger/earn"))).size();
    }

    /** Insert (replacing any prior) a Source-Aggregate Cap row for CARD_SPEND (V3 earn_source_cap). */
    private void seedSourceCap(Long dailyCap, Long monthlyCap, Long lifetimeCap) {
        try (java.sql.Connection c = dataSource.getConnection()) {
            try (var del = c.prepareStatement(
                    "DELETE FROM earn_source_cap WHERE program_id = ? AND earn_source_code = ?")) {
                del.setLong(1, PROGRAM);
                del.setString(2, "CARD_SPEND");
                del.executeUpdate();
            }
            try (var ins = c.prepareStatement(
                    "INSERT INTO earn_source_cap (program_id, earn_source_code, daily_cap, monthly_cap, lifetime_cap) "
                            + "VALUES (?, ?, ?, ?, ?)")) {
                ins.setLong(1, PROGRAM);
                ins.setString(2, "CARD_SPEND");
                if (dailyCap == null) ins.setNull(3, java.sql.Types.BIGINT); else ins.setLong(3, dailyCap);
                if (monthlyCap == null) ins.setNull(4, java.sql.Types.BIGINT); else ins.setLong(4, monthlyCap);
                if (lifetimeCap == null) ins.setNull(5, java.sql.Types.BIGINT); else ins.setLong(5, lifetimeCap);
                ins.executeUpdate();
            }
        } catch (java.sql.SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private JsonNode pointsEarnedOutbox() throws Exception {
        OutboxEntry row = outbox.findAll().stream()
                .filter(o -> o.getEventType().equals("PointsEarned"))
                .findFirst().orElseThrow(() -> new AssertionError("no PointsEarned outbox row"));
        return mapper.readTree(row.getPayload());
    }

    // --- tests ---------------------------------------------------------------

    @Test
    void engineEarnWritesLedgerEntryAndEnqueuesPointsEarned() throws Exception {
        activeRule(RATE_RULE);

        engine.process(cardSpend("evt-earn-1", 25000), MEMBER_REF);

        assertThat(ledgerCalls()).isEqualTo(1);
        assertThat(CORE.findAll(postRequestedFor(urlEqualTo("/ledger/earn"))).get(0).getBodyAsString())
                .contains("\"redeemableDelta\":2").contains("\"sourceRef\":\"evt-earn-1:");
        JsonNode evt = pointsEarnedOutbox();
        assertThat(evt.path("memberId").asLong()).isEqualTo(MEMBER);
        assertThat(evt.path("totalRedeemableDelta").asLong()).isEqualTo(2);
        assertThat(idempotency.existsById("evt-earn-1")).isTrue();
    }

    @Test
    void replayShortCircuitsAndCallsLedgerOnce() {
        activeRule(RATE_RULE);
        engine.process(cardSpend("evt-dup", 25000), MEMBER_REF);
        var replay = engine.process(cardSpend("evt-dup", 25000), MEMBER_REF);

        assertThat(replay.replayed()).isTrue();
        assertThat(ledgerCalls()).isEqualTo(1);
    }

    @Test
    void everyMatchingRuleFiresItsOwnLedgerEntry() throws Exception {
        activeRule(RATE_RULE);                                                    // 2 pts
        activeRule("""
            {"dslVersion":1,"earnSource":"CARD_SPEND",
             "rows":[{"when":{"amount":"*"},"earn":{"type":"FIXED","points":50}}]}""");   // 50 pts

        engine.process(cardSpend("evt-multi", 25000), MEMBER_REF);

        assertThat(ledgerCalls()).isEqualTo(2);
        assertThat(pointsEarnedOutbox().path("totalRedeemableDelta").asLong()).isEqualTo(52);
    }

    @Test
    void capExhaustionDropsTheSecondEvent() {
        // perMemberPerDay = 2, FIXED 2 -> first event consumes the whole day; second is dropped.
        activeRule("""
            {"dslVersion":1,"earnSource":"CARD_SPEND",
             "rows":[{"when":{"amount":"*"},"earn":{"type":"FIXED","points":2}}],
             "caps":{"perMemberPerDay":2}}""");

        engine.process(cardSpend("evt-cap-a", 1), MEMBER_REF);
        engine.process(cardSpend("evt-cap-b", 1), MEMBER_REF);

        assertThat(ledgerCalls()).isEqualTo(1);   // second fire dropped by the cap
    }

    @Test
    @Transactional
    void capServiceConditionalDecrementIsAtomic() {
        RuleDsl.Caps fivePerDay = new RuleDsl.Caps(null, 5, null, null);
        Instant now = Instant.parse("2026-05-30T10:00:00Z");

        assertThat(capService.tryConsume(PROGRAM, 1L, MEMBER, fivePerDay, 3, now)).isTrue();   // 5 - 3 = 2
        assertThat(capService.tryConsume(PROGRAM, 1L, MEMBER, fivePerDay, 3, now)).isFalse();  // 2 < 3 -> exhausted
        assertThat(capService.tryConsume(PROGRAM, 1L, MEMBER, fivePerDay, 2, now)).isTrue();   // 2 - 2 = 0
    }

    @Test
    void createRuleRejectsInvalidDslAndAcceptsValid() {
        Map<String, Object> bad = Map.of("earnSourceId", cardSpendSourceId(),
                "dslJson", Map.of("dslVersion", 1, "earnSource", "CARD_SPEND"));   // missing rows
        StringBuilder badBody = new StringBuilder();
        assertThat(statusOf("/programs/1/rules", bad, badBody)[0]).isEqualTo(400);
        assertThat(badBody.toString()).contains("DSL_INVALID");

        Map<String, Object> good = Map.of("earnSourceId", cardSpendSourceId(),
                "dslJson", Map.of("dslVersion", 1, "earnSource", "CARD_SPEND",
                        "rows", List.of(Map.of("when", Map.of("amount", "*"),
                                "earn", Map.of("type", "FIXED", "points", 5)))));
        StringBuilder goodBody = new StringBuilder();
        assertThat(statusOf("/programs/1/rules", good, goodBody)[0]).isEqualTo(201);
        assertThat(goodBody.toString()).contains("\"status\":\"DRAFT\"");
    }

    @Test
    void dryRunReplaysWindowWithNoSideEffects() {
        long ruleId = activeRule(RATE_RULE);
        Instant t = Instant.parse("2026-05-30T09:00:00Z");
        // Seed the replay store directly (as the consumer would have). save() is auto-transactional.
        replayStore.save(io.github.batnam.loyalty.earning.replay.EarnEventLog.of("replay-1", "CARD_SPEND", CUSTOMER, "{\"amount\":30000}", t));   // -> 3
        replayStore.save(io.github.batnam.loyalty.earning.replay.EarnEventLog.of("replay-2", "CARD_SPEND", CUSTOMER, "{\"amount\":20000}", t));   // -> 2

        Map<String, Object> body = Map.of("from", "2026-05-30T00:00:00Z", "to", "2026-05-30T23:59:59Z");
        StringBuilder report = new StringBuilder();
        assertThat(statusOf("/programs/1/rules/" + ruleId + "/dry-run", body, report)[0]).isEqualTo(200);

        assertThat(report.toString()).contains("\"matchedEvents\":2").contains("\"totalRedeemable\":5");
        assertThat(ledgerCalls()).isZero();                       // no Ledger write
        assertThat(caps.count()).isZero();                        // no cap_counter decrement/creation
    }

    @Test
    void earnEventEndToEndViaKafka() throws Exception {
        activeRule(RATE_RULE);
        String message = mapper.writeValueAsString(cardSpend("evt-kafka-1", 25000));

        template.send("loyalty.earn.translated.v1", String.valueOf(CUSTOMER), message);

        JsonNode evt = awaitEvent("loyalty.earning.points_earned.v1", "evt-kafka-1");
        assertThat(evt.path("eventType").asText()).isEqualTo("PointsEarned");
        assertThat(evt.path("memberId").asLong()).isEqualTo(MEMBER);
        assertThat(evt.path("totalRedeemableDelta").asLong()).isEqualTo(2);
    }

    @Test
    void sourceAggregateCapClampsTotalAcrossRulesForTheSource() throws Exception {
        // CAP-005: two ACTIVE rules for CARD_SPEND each award FIXED 10 (qualifying=redeemable=10),
        // so capPoints per fire = 10, total demand = 20. A program-level source daily cap of 15
        // must clamp the AGGREGATE to 15: first fire takes 10, the second is partial-granted 5.
        seedSourceCap(15L, null, null);
        activeRule("""
            {"dslVersion":1,"earnSource":"CARD_SPEND",
             "rows":[{"when":{"amount":"*"},"earn":{"type":"FIXED","points":10}}]}""");
        activeRule("""
            {"dslVersion":1,"earnSource":"CARD_SPEND",
             "rows":[{"when":{"amount":"*"},"earn":{"type":"FIXED","points":10}}]}""");

        engine.process(cardSpend("evt-srccap-1", 1), MEMBER_REF);

        // Both rules fire (each granted > 0), but the source cap drops the over-the-cap portion:
        // 10 (first rule, full) + 5 (second rule, scaled down) = 15.
        assertThat(ledgerCalls()).isEqualTo(2);
        assertThat(pointsEarnedOutbox().path("totalRedeemableDelta").asLong()).isEqualTo(15);
    }

    @Test
    void monthlyCapExhaustionDropsTheOverflowingEvent() {
        // CAP-003 (perMemberPerMonth): monthly cap = 5, FIXED 3. Both events fall in the same UTC month
        // (cardSpend fires at 2026-05-30T10:00Z), so they share the MONTH:2026-05 counter. First fire
        // consumes 3 (5→2); the second demands 3 but only 2 remain — the per-rule cap drops the whole fire.
        activeRule("""
            {"dslVersion":1,"earnSource":"CARD_SPEND",
             "rows":[{"when":{"amount":"*"},"earn":{"type":"FIXED","points":3}}],
             "caps":{"perMemberPerMonth":5}}""");

        engine.process(cardSpend("evt-mcap-a", 1), MEMBER_REF);
        engine.process(cardSpend("evt-mcap-b", 1), MEMBER_REF);

        assertThat(ledgerCalls()).isEqualTo(1);   // second fire dropped by the monthly cap
    }

    @Test
    void lifetimeCapHoldsAcrossEventsAndSurvivesPurge() {
        // CAP-004 / CAP-008 (perMemberPerRule): a one-time lifetime cap = 1, FIXED 1. The first matching
        // event awards once; every later matching event is dropped — the LIFE window never refills.
        long ruleId = activeRule("""
            {"dslVersion":1,"earnSource":"CARD_SPEND",
             "rows":[{"when":{"amount":"*"},"earn":{"type":"FIXED","points":1}}],
             "caps":{"perMemberPerRule":1}}""");

        engine.process(cardSpend("evt-lcap-a", 1), MEMBER_REF);
        engine.process(cardSpend("evt-lcap-b", 1), MEMBER_REF);
        engine.process(cardSpend("evt-lcap-c", 1), MEMBER_REF);

        assertThat(ledgerCalls()).isEqualTo(1);   // only the first fire is ever awarded

        // The LIFE counter row exists (window_key "LIFE", expires_at NULL) and must NOT be purged: the
        // nightly purge only removes rows with a non-NULL expires_at in the past.
        long lifeRows = lifeCounterRows(ruleId);
        assertThat(lifeRows).isEqualTo(1);
        capPurgeJob.purge();                              // run the real nightly purge (its own @Transactional)
        assertThat(lifeCounterRows(ruleId)).isEqualTo(1);   // lifetime row (expires_at NULL) survives the purge
    }

    /** Count cap_counter LIFE rows for this rule+member (used to assert lifetime counters are never purged). */
    private long lifeCounterRows(long ruleId) {
        try (java.sql.Connection c = dataSource.getConnection();
             var ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM cap_counter WHERE program_id = ? AND rule_id = ? "
                             + "AND member_id = ? AND window_key = 'LIFE' AND expires_at IS NULL")) {
            ps.setLong(1, PROGRAM);
            ps.setLong(2, ruleId);
            ps.setLong(3, MEMBER);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (java.sql.SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void tierEarnMultiplierMultipliesAwardedPoints() throws Exception {
        // RULE-006 / Gap 5B: a tierMultiplier:true rule multiplies the base award by the member's
        // tier earnMultiplier (here 2.0, surfaced by core's /members/lookup → MemberRef.earnMultiplier).
        MemberRef tierMember = new MemberRef(MEMBER, PROGRAM, "ACTIVE", new java.math.BigDecimal("2.0"));
        activeRule("""
            {"dslVersion":1,"earnSource":"CARD_SPEND","tierMultiplier":true,
             "rows":[{"when":{"amount":"*"},"earn":{"type":"FIXED","points":10}}]}""");

        engine.process(cardSpend("evt-tier-1", 1), tierMember);

        // base 10 × tier multiplier 2.0 = 20.
        assertThat(ledgerCalls()).isEqualTo(1);
        assertThat(CORE.findAll(postRequestedFor(urlEqualTo("/ledger/earn"))).get(0).getBodyAsString())
                .contains("\"redeemableDelta\":20");
        assertThat(pointsEarnedOutbox().path("totalRedeemableDelta").asLong()).isEqualTo(20);
    }

    private JsonNode awaitEvent(String topic, String marker) throws Exception {
        try (Consumer<String, String> consumer = newConsumer(topic)) {
            long deadline = System.currentTimeMillis() + 30_000;
            while (System.currentTimeMillis() < deadline) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> r : records) {
                    if (r.value() != null && r.value().contains(marker)) {
                        return mapper.readTree(r.value());
                    }
                }
            }
        }
        throw new AssertionError("no event containing '" + marker + "' on " + topic);
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
