package io.github.batnam.loyalty.redemption;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.github.batnam.loyalty.redemption.outbox.OutboxEntry;
import io.github.batnam.loyalty.redemption.outbox.OutboxRepository;
import io.github.batnam.loyalty.redemption.reward.Reward;
import io.github.batnam.loyalty.redemption.reward.RewardCatalogue;
import io.github.batnam.loyalty.redemption.reward.RewardEligibilityRepository;
import io.github.batnam.loyalty.redemption.reward.RewardInventoryRepository;
import io.github.batnam.loyalty.redemption.reward.RewardRepository;
import io.github.batnam.loyalty.redemption.reward.RewardStatus;
import io.github.batnam.loyalty.redemption.saga.RedemptionIdempotencyRepository;
import io.github.batnam.loyalty.redemption.saga.SagaRepository;
import io.github.batnam.loyalty.redemption.saga.SagaStatus;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.testcontainers.DockerClientFactory.instance;

/**
 * End-to-end loyalty-redemption test against real Postgres + Kafka (Testcontainers) with all outbound
 * dependencies (loyalty-core Reservation API, Payment Hub, 3rd-party voucher provider) stubbed by one
 * WireMock server. Exercises the two-phase Saga over REST: sync Cashback (reserve→commit→outbox),
 * eligibility reject (409), insufficient-balance from core (409 + inventory restore), the async
 * 3rd-party-voucher path (202 → resume via Kafka → COMMITTED), idempotent submit replay, reward CRUD +
 * approval gate, and inventory exhaustion (atomic conditional decrement). Skipped when Docker is absent.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class RedemptionIntegrationTest {

    private static final long PROGRAM = 1L;
    private static final long MEMBER = 42L;
    private static final long CUSTOMER = 42L;
    private static final String ACCOUNT = "0011000123";
    private static final long RESERVATION = 555L;

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    @Container
    static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

    // One WireMock stands in for core + Payment Hub + voucher partner (distinct paths, no conflict).
    static final WireMockServer STUBS = new WireMockServer(options().dynamicPort());

    static {
        STUBS.start();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("redemption.core.base-url", STUBS::baseUrl);
        registry.add("redemption.payment-hub.base-url", STUBS::baseUrl);
        registry.add("redemption.voucher-partner.base-url", STUBS::baseUrl);
        registry.add("redemption.campaign.base-url", STUBS::baseUrl);
    }

    @BeforeAll
    static void requireDocker() {
        assumeTrue(instance().isDockerAvailable(), "Docker not available — skipping Testcontainers IT");
    }

    @Autowired RewardCatalogue catalogue;
    @Autowired SagaRepository sagas;
    @Autowired RedemptionIdempotencyRepository idempotency;
    @Autowired OutboxRepository outbox;
    @Autowired RewardRepository rewards;
    @Autowired RewardInventoryRepository inventories;
    @Autowired RewardEligibilityRepository eligibilities;
    @Autowired KafkaTemplate<String, String> template;
    @Autowired ObjectMapper mapper;
    @Autowired Environment env;

    private RestClient http;

    @BeforeEach
    void setUp() {
        // engine writes commit, so clear mutable state between tests (audit log is insert-only; reward_type
        // is seeded — both left intact). Order respects FKs.
        idempotency.deleteAll();
        sagas.deleteAll();
        outbox.deleteAll();
        inventories.deleteAll();
        eligibilities.deleteAll();
        rewards.deleteAll();

        http = RestClient.create("http://localhost:" + env.getProperty("local.server.port"));

        STUBS.resetAll();
        // core: projection (rich balance), reserve (HELD), commit (Redeemed entry), release (bodiless).
        stubProjection(1_000_000);
        STUBS.stubFor(post(urlEqualTo("/reservations")).willReturn(okJson("""
                {"reservationId":%d,"memberId":%d,"programId":%d,"points":5000,"status":"HELD",
                 "externalRef":null,"expiresAt":"2026-05-30T10:15:00Z"}""".formatted(RESERVATION, MEMBER, PROGRAM))
                .withStatus(201)));
        STUBS.stubFor(post(urlPathMatching("/reservations/\\d+/commit")).willReturn(okJson("""
                {"reservationId":%d,"memberId":%d,"programId":%d,"points":5000,"status":"COMMITTED",
                 "externalRef":null,"expiresAt":"2026-05-30T10:15:00Z","ledgerEntryId":900}"""
                .formatted(RESERVATION, MEMBER, PROGRAM)).withStatus(200)));
        STUBS.stubFor(post(urlPathMatching("/reservations/\\d+/release")).willReturn(aResponse().withStatus(200)));
        // Payment Hub + voucher partner.
        STUBS.stubFor(post(urlEqualTo("/disbursements")).willReturn(okJson("{\"externalRef\":\"PH-TXN-1\"}")));
        STUBS.stubFor(post(urlEqualTo("/provision")).willReturn(okJson("{\"externalRef\":\"VP-REF-1\"}").withStatus(202)));
    }

    private void stubProjection(long redeemableBalance) {
        STUBS.stubFor(get(urlPathMatching("/members/\\d+/programs/\\d+/projection")).willReturn(okJson("""
                {"memberId":%d,"programId":%d,"redeemableBalance":%d,"qualifyingBalance":0,
                 "tierCode":"GOLD","status":"ACTIVE","asOf":"2026-05-30T10:00:00Z"}"""
                .formatted(MEMBER, PROGRAM, redeemableBalance))));
    }

    // --- helpers -------------------------------------------------------------

    private long activeReward(String typeCode, long cost, String paramsJson, Long inventoryTotal) {
        try {
            Reward r = catalogue.createReward("op", PROGRAM, typeCode, typeCode + " reward", cost,
                    mapper.readValue(paramsJson, Object.class), null, inventoryTotal);
            catalogue.updateReward("op", r.getRewardId(), RewardStatus.ACTIVE, null, "BEP-APV-1");
            return r.getRewardId();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** POST JSON with optional headers; capture [status] and append body to {@code out} (no throw on 4xx/5xx). */
    private int postJson(String uri, Object body, Map<String, String> headers, StringBuilder out) {
        return http.post().uri(uri).contentType(MediaType.APPLICATION_JSON)
                .headers(h -> headers.forEach(h::add))
                .body(body)
                .exchange((req, res) -> {
                    out.append(new String(res.getBody().readAllBytes()));
                    return res.getStatusCode().value();
                });
    }

    private int patch(String uri, Object body, StringBuilder out) {
        return http.patch().uri(uri).contentType(MediaType.APPLICATION_JSON).body(body)
                .exchange((req, res) -> {
                    out.append(new String(res.getBody().readAllBytes()));
                    return res.getStatusCode().value();
                });
    }

    private Map<String, Object> redemption(long rewardId) {
        return Map.of("memberId", MEMBER, "customerId", CUSTOMER, "accountNumber", ACCOUNT,
                "programId", PROGRAM, "rewardId", rewardId);
    }

    private int reserveCalls() {
        return STUBS.findAll(postRequestedFor(urlEqualTo("/reservations"))).size();
    }

    private OutboxEntry outboxOf(String eventType) {
        return outbox.findAll().stream().filter(o -> o.getEventType().equals(eventType)).findFirst()
                .orElseThrow(() -> new AssertionError("no " + eventType + " outbox row"));
    }

    // --- tests ---------------------------------------------------------------

    @Test
    void syncCashbackRedemptionReservesCommitsAndEmitsCompleted() {
        long rewardId = activeReward("CASHBACK", 5000, "{\"amount\":50000,\"currency\":\"VND\"}", null);

        StringBuilder body = new StringBuilder();
        int status = postJson("/redemptions", redemption(rewardId), Map.of("Idempotency-Key", "k-1"), body);

        assertThat(status).isEqualTo(200);
        assertThat(body.toString()).contains("\"status\":\"COMMITTED\"").contains("\"ledgerEntryId\":900");
        // Payment Hub is addressed by CIF + CASA, never by Loyalty's memberId.
        assertThat(STUBS.findAll(postRequestedFor(urlEqualTo("/disbursements"))
                .withRequestBody(equalToJson(
                        "{\"customerId\":" + CUSTOMER + ",\"accountNumber\":\"" + ACCOUNT + "\"}",
                        true, true)))).hasSize(1);
        assertThat(STUBS.findAll(postRequestedFor(urlPathMatching("/reservations/\\d+/commit")))).hasSize(1);
        assertThat(outboxOf("RedemptionCompleted").getPayload()).contains("\"externalRef\":\"PH-TXN-1\"");
    }

    @Test
    void eligibilityRejectReturns409WithoutReserving() {
        stubProjection(100);   // below the 5000 cost
        long rewardId = activeReward("CASHBACK", 5000, "{\"amount\":50000,\"currency\":\"VND\"}", null);

        StringBuilder body = new StringBuilder();
        int status = postJson("/redemptions", redemption(rewardId), Map.of("Idempotency-Key", "k-2"), body);

        assertThat(status).isEqualTo(409);
        assertThat(body.toString()).contains("INSUFFICIENT_BALANCE");
        assertThat(reserveCalls()).isZero();
    }

    @Test
    void insufficientBalanceFromCoreReturns409AndRestoresInventory() {
        // Projection looks fine, but core rejects the reserve with 409 (authoritative). Inventory=1 must
        // be restored so it isn't leaked.
        STUBS.stubFor(post(urlEqualTo("/reservations")).willReturn(
                aResponse().withStatus(409).withHeader("Content-Type", "application/json")
                        .withBody("{\"code\":\"INSUFFICIENT_BALANCE\"}")));
        long rewardId = activeReward("CASHBACK", 5000, "{\"amount\":50000,\"currency\":\"VND\"}", 1L);

        StringBuilder body = new StringBuilder();
        int status = postJson("/redemptions", redemption(rewardId), Map.of("Idempotency-Key", "k-3"), body);

        assertThat(status).isEqualTo(409);
        assertThat(body.toString()).contains("INSUFFICIENT_BALANCE");
        assertThat(inventories.findById(rewardId).orElseThrow().getRemaining()).isEqualTo(1);   // restored
    }

    @Test
    void asyncVoucherReturns202ThenResumeCommits() throws Exception {
        long rewardId = activeReward("THIRD_PARTY_VOUCHER", 5000, "{\"sku\":\"COFFEE\"}", null);

        StringBuilder body = new StringBuilder();
        int status = postJson("/redemptions", redemption(rewardId), Map.of("Idempotency-Key", "k-4"), body);

        assertThat(status).isEqualTo(202);
        JsonNode submitted = mapper.readTree(body.toString());
        assertThat(submitted.path("status").asText()).isEqualTo("FULFILLING");
        long sagaId = submitted.path("redemptionId").asLong();
        assertThat(STUBS.findAll(postRequestedFor(urlPathMatching("/reservations/\\d+/commit")))).isEmpty();

        // Partner webhook arrives (bridge → resume topic). Resume Consumer finishes the Saga.
        String resume = """
                {"eventId":"r-1","eventType":"FulfillmentResume","externalRef":"VP-REF-1",
                 "outcome":"SUCCESS","payload":{"voucherCode":"VCH-COFFEE-9"}}""";
        template.send("loyalty.fulfillment.resume.v1", "VP-REF-1", resume);

        SagaStatus finalStatus = awaitSagaStatus(sagaId);
        assertThat(finalStatus).isEqualTo(SagaStatus.COMMITTED);
        assertThat(sagas.findById(sagaId).orElseThrow().getExternalRef()).isEqualTo("VCH-COFFEE-9");
        assertThat(STUBS.findAll(postRequestedFor(urlPathMatching("/reservations/\\d+/commit")))).hasSize(1);
    }

    @Test
    void idempotentSubmitReplayReturnsOriginalAndReservesOnce() {
        long rewardId = activeReward("CASHBACK", 5000, "{\"amount\":50000,\"currency\":\"VND\"}", null);

        StringBuilder first = new StringBuilder();
        postJson("/redemptions", redemption(rewardId), Map.of("Idempotency-Key", "k-dup"), first);
        StringBuilder second = new StringBuilder();
        int status = postJson("/redemptions", redemption(rewardId), Map.of("Idempotency-Key", "k-dup"), second);

        assertThat(status).isEqualTo(200);
        assertThat(reserveCalls()).isEqualTo(1);   // second submit short-circuited
        assertThat(mapper(second).path("redemptionId").asLong())
                .isEqualTo(mapper(first).path("redemptionId").asLong());
    }

    @Test
    void rewardCrudAndApprovalGate() throws Exception {
        // Create a DRAFT via REST.
        Map<String, Object> create = Map.of("rewardTypeCode", "CASHBACK", "name", "Test Reward",
                "pointCost", 5000, "fulfillmentParams", Map.of("amount", 50000, "currency", "VND"));
        StringBuilder created = new StringBuilder();
        assertThat(postJson("/programs/1/rewards", create, Map.of(), created)).isEqualTo(201);
        assertThat(created.toString()).contains("\"status\":\"DRAFT\"");
        long rewardId = mapper.readTree(created.toString()).path("rewardId").asLong();

        // ACTIVE without approval ref → 409 MISSING_APPROVAL.
        StringBuilder noApproval = new StringBuilder();
        assertThat(patch("/rewards/" + rewardId, Map.of("status", "ACTIVE"), noApproval)).isEqualTo(409);
        assertThat(noApproval.toString()).contains("MISSING_APPROVAL");

        // ACTIVE with approval ref → 200 ACTIVE.
        StringBuilder approved = new StringBuilder();
        assertThat(patch("/rewards/" + rewardId,
                Map.of("status", "ACTIVE", "bepApprovalRef", "BEP-APV-9"), approved)).isEqualTo(200);
        assertThat(approved.toString()).contains("\"status\":\"ACTIVE\"");
    }

    @Test
    void inventoryExhaustionDropsTheSecondRedemption() {
        long rewardId = activeReward("CASHBACK", 5000, "{\"amount\":50000,\"currency\":\"VND\"}", 1L);

        StringBuilder first = new StringBuilder();
        assertThat(postJson("/redemptions", redemption(rewardId), Map.of("Idempotency-Key", "inv-1"), first))
                .isEqualTo(200);
        StringBuilder second = new StringBuilder();
        int status = postJson("/redemptions", redemption(rewardId), Map.of("Idempotency-Key", "inv-2"), second);

        assertThat(status).isEqualTo(409);
        assertThat(second.toString()).contains("INVENTORY_EXHAUSTED");
        assertThat(inventories.findById(rewardId).orElseThrow().getRemaining()).isZero();
    }

    private JsonNode mapper(StringBuilder body) {
        try {
            return mapper.readTree(body.toString());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private SagaStatus awaitSagaStatus(long sagaId) {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            SagaStatus s = sagas.findById(sagaId).map(x -> x.getStatus()).orElse(null);
            if (s != null && s.isTerminal()) {
                return s;
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError("saga " + sagaId + " did not reach a terminal status in time");
    }
}
