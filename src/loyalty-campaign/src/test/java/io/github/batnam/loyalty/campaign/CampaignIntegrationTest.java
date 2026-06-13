package io.github.batnam.loyalty.campaign;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.batnam.loyalty.campaign.drawing.DrawingScheduler;
import io.github.batnam.loyalty.campaign.drawing.DrawingStatus;
import io.github.batnam.loyalty.campaign.drawing.DrawingRepository;
import io.github.batnam.loyalty.campaign.drawing.WinnerRecordRepository;
import io.github.batnam.loyalty.campaign.drawing.WinnerSelectionService;
import io.github.batnam.loyalty.campaign.outbox.OutboxEntry;
import io.github.batnam.loyalty.campaign.outbox.OutboxRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.testcontainers.DockerClientFactory.instance;

/**
 * End-to-end loyalty-campaign test against real Postgres + Kafka (Testcontainers). No WireMock — campaign
 * has no synchronous downstream dependency; it only serves REST and *emits* events through the outbox.
 * Exercises: Campaign CRUD + the LIVE approval gate (economic Campaign), Drawing authoring, the T-13 entry
 * surface (201 / idempotent 200 / window-gated 409), seeded Winner Selection (K winner_record rows +
 * DrawingCompleted/WinnerSelected outbox + audit-replay seed), the zero-entry VOID path, and the
 * scheduler-driven due-drawing fire. Skipped when Docker is absent.
 *
 * <p>{@code winner_record} + {@code campaign_audit_log} are insert-only (DB triggers reject DELETE), so the
 * suite does not wipe tables between tests — every assertion is scoped to the unique IDs it creates.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class CampaignIntegrationTest {

    private static final long PROGRAM = 1L;

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    @Container
    static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        // Disable the background Drawing Scheduler ("-" = no trigger) so it can't race our explicit
        // selectWinner / fireDueDrawings calls; the IT drives Winner Selection deterministically itself.
        registry.add("campaign.scheduler.poll-cron", () -> "-");
    }

    @BeforeAll
    static void requireDocker() {
        assumeTrue(instance().isDockerAvailable(), "Docker not available — skipping Testcontainers IT");
    }

    @Autowired ObjectMapper mapper;
    @Autowired Environment env;
    @Autowired WinnerSelectionService winnerSelection;
    @Autowired DrawingScheduler scheduler;
    @Autowired DrawingRepository drawings;
    @Autowired WinnerRecordRepository winnerRecords;
    @Autowired OutboxRepository outbox;

    private RestClient http;

    @BeforeEach
    void setUp() {
        http = RestClient.create("http://localhost:" + env.getProperty("local.server.port"));
    }

    // --- helpers -------------------------------------------------------------

    /** POST JSON with optional headers; capture [status], append body to {@code out} (no throw on 4xx/5xx). */
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

    private JsonNode json(StringBuilder body) {
        try {
            return mapper.readTree(body.toString());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Create a LIVE economic Campaign over REST, returning its id. */
    private long liveCampaign() {
        Map<String, Object> create = Map.of("name", "Dining x2",
                "startsAt", "2026-05-01T00:00:00Z", "endsAt", "2026-12-31T00:00:00Z",
                "multiplierRule", Map.of("multiplier", 2));
        StringBuilder created = new StringBuilder();
        assertThat(postJson("/programs/1/campaigns", create, Map.of(), created)).isEqualTo(201);
        long id = json(created).path("campaignId").asLong();
        StringBuilder live = new StringBuilder();
        assertThat(patch("/campaigns/" + id, Map.of("status", "LIVE", "bepApprovalRef", "BEP-APV-1"), live))
                .isEqualTo(200);
        return id;
    }

    /** Create a Drawing under {@code campaignId}. windowOpen=true => entries accepted now. */
    private long drawing(long campaignId, String strategy, int winnersCount, boolean windowOpen, Instant drawAt) {
        Instant start = Instant.now().minus(1, ChronoUnit.HOURS);
        Instant end = windowOpen ? Instant.now().plus(1, ChronoUnit.HOURS)
                                 : Instant.now().minus(1, ChronoUnit.MINUTES);
        Map<String, Object> create = Map.of(
                "prize", Map.of("prizeRewardId", 1, "label", "Cashback"),
                "entryWindowStart", start.toString(), "entryWindowEnd", end.toString(),
                "drawAt", drawAt.toString(), "selectionStrategy", strategy, "winnersCount", winnersCount);
        StringBuilder created = new StringBuilder();
        assertThat(postJson("/campaigns/" + campaignId + "/drawings", create, Map.of(), created)).isEqualTo(201);
        return json(created).path("drawingId").asLong();
    }

    private int recordEntry(long drawingId, long memberId, String idempotencyKey, StringBuilder out) {
        return postJson("/drawings/" + drawingId + "/entries",
                Map.of("memberId", memberId, "sagaId", 99, "idempotencyKey", idempotencyKey), Map.of(), out);
    }

    private List<OutboxEntry> outboxFor(String eventType, long drawingId) {
        return outbox.findAll().stream()
                .filter(o -> o.getEventType().equals(eventType))
                .filter(o -> o.getPayload().contains("\"drawingId\":" + drawingId))
                .toList();
    }

    // --- tests ---------------------------------------------------------------

    @Test
    void campaignApprovalGateBlocksLiveWithoutApprovalRef() {
        Map<String, Object> create = Map.of("name", "Dining x2",
                "startsAt", "2026-05-01T00:00:00Z", "endsAt", "2026-12-31T00:00:00Z",
                "multiplierRule", Map.of("multiplier", 2));
        StringBuilder created = new StringBuilder();
        assertThat(postJson("/programs/1/campaigns", create, Map.of(), created)).isEqualTo(201);
        assertThat(created.toString()).contains("\"status\":\"DRAFT\"");
        long id = json(created).path("campaignId").asLong();

        StringBuilder noApproval = new StringBuilder();
        assertThat(patch("/campaigns/" + id, Map.of("status", "LIVE"), noApproval)).isEqualTo(409);
        assertThat(noApproval.toString()).contains("MISSING_APPROVAL");

        StringBuilder approved = new StringBuilder();
        assertThat(patch("/campaigns/" + id, Map.of("status", "LIVE", "bepApprovalRef", "BEP-APV-9"), approved))
                .isEqualTo(200);
        assertThat(approved.toString()).contains("\"status\":\"LIVE\"");
    }

    @Test
    void illegalCampaignTransitionIsRejected() {
        Map<String, Object> create = Map.of("name", "Awareness",
                "startsAt", "2026-05-01T00:00:00Z", "endsAt", "2026-12-31T00:00:00Z");
        StringBuilder created = new StringBuilder();
        postJson("/programs/1/campaigns", create, Map.of(), created);
        long id = json(created).path("campaignId").asLong();

        StringBuilder ended = new StringBuilder();
        assertThat(patch("/campaigns/" + id, Map.of("status", "ENDED"), ended)).isEqualTo(409);
        assertThat(ended.toString()).contains("ILLEGAL_TRANSITION");
    }

    @Test
    void entryIsRecordedThenIdempotentlyReplayed() {
        long campaign = liveCampaign();
        long drawingId = drawing(campaign, "SEEDED_RNG", 1, true, Instant.now().plus(1, ChronoUnit.DAYS));

        StringBuilder first = new StringBuilder();
        assertThat(recordEntry(drawingId, 42L, "entry-k-1", first)).isEqualTo(201);
        long entryId = json(first).path("entryId").asLong();

        StringBuilder replay = new StringBuilder();
        assertThat(recordEntry(drawingId, 42L, "entry-k-1", replay)).isEqualTo(200);   // idempotent
        assertThat(json(replay).path("entryId").asLong()).isEqualTo(entryId);          // same row
    }

    @Test
    void entryOutsideTheWindowIsRejected() {
        long campaign = liveCampaign();
        long drawingId = drawing(campaign, "SEEDED_RNG", 1, false, Instant.now().plus(1, ChronoUnit.DAYS));

        StringBuilder out = new StringBuilder();
        assertThat(recordEntry(drawingId, 42L, "late-k-1", out)).isEqualTo(409);
        assertThat(out.toString()).contains("DRAWING_CLOSED");
    }

    @Test
    void seededDrawingSelectsKWinnersWithAuditableSeedAndEmitsEvents() {
        long campaign = liveCampaign();
        long drawingId = drawing(campaign, "SEEDED_RNG", 3, true, Instant.now().minus(1, ChronoUnit.MINUTES));
        for (int i = 0; i < 8; i++) {
            assertThat(recordEntry(drawingId, 100L + i, "w-" + drawingId + "-" + i, new StringBuilder()))
                    .isEqualTo(201);
        }

        winnerSelection.selectWinner(drawingId);

        assertThat(drawings.findById(drawingId).orElseThrow().getStatus()).isEqualTo(DrawingStatus.CLOSED);
        var winners = winnerRecords.findByDrawingIdOrderByWinnerIndexAsc(drawingId);
        assertThat(winners).hasSize(3);
        assertThat(winners).extracting(w -> w.getWinnerIndex()).doesNotHaveDuplicates();
        assertThat(winners).allSatisfy(w -> assertThat(w.getSeedHex()).hasSize(64));   // replayable seed
        assertThat(outboxFor("DrawingCompleted", drawingId)).hasSize(1);
        assertThat(outboxFor("WinnerSelected", drawingId)).hasSize(3);

        // Audit view over REST.
        StringBuilder view = new StringBuilder();
        assertThat(postJson0Get("/drawings/" + drawingId + "/winners", view)).isEqualTo(200);
        assertThat(json(view)).hasSize(3);
    }

    @Test
    void zeroEntryDrawingClosesVoidAndEmitsDrawingVoid() {
        long campaign = liveCampaign();
        long drawingId = drawing(campaign, "SEEDED_RNG", 1, true, Instant.now().minus(1, ChronoUnit.MINUTES));

        winnerSelection.selectWinner(drawingId);

        assertThat(drawings.findById(drawingId).orElseThrow().getStatus()).isEqualTo(DrawingStatus.VOID);
        assertThat(winnerRecords.findByDrawingIdOrderByWinnerIndexAsc(drawingId)).isEmpty();
        assertThat(outboxFor("DrawingVoid", drawingId)).hasSize(1);
    }

    @Test
    void schedulerFiresDueDrawings() {
        long campaign = liveCampaign();
        long drawingId = drawing(campaign, "FIRST_N", 2, true, Instant.now().minus(1, ChronoUnit.MINUTES));
        recordEntry(drawingId, 200L, "s-" + drawingId + "-a", new StringBuilder());
        recordEntry(drawingId, 201L, "s-" + drawingId + "-b", new StringBuilder());

        scheduler.fireDueDrawings();

        assertThat(drawings.findById(drawingId).orElseThrow().getStatus()).isEqualTo(DrawingStatus.CLOSED);
        var winners = winnerRecords.findByDrawingIdOrderByWinnerIndexAsc(drawingId);
        assertThat(winners).hasSize(2);
        // FIRST_N: winners are the first two by arrival, no seed.
        assertThat(winners).extracting(w -> w.getMemberId()).containsExactly(200L, 201L);
        assertThat(winners).allSatisfy(w -> assertThat(w.getSeedHex()).isNull());
    }

    private int postJson0Get(String uri, StringBuilder out) {
        return http.get().uri(uri)
                .exchange((req, res) -> {
                    out.append(new String(res.getBody().readAllBytes()));
                    return res.getStatusCode().value();
                });
    }
}
