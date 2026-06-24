package io.github.batnam.loyalty.campaign;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.batnam.loyalty.campaign.drawing.DrawingScheduler;
import io.github.batnam.loyalty.campaign.drawing.DrawingStatus;
import io.github.batnam.loyalty.campaign.drawing.DrawingRepository;
import io.github.batnam.loyalty.campaign.drawing.WinnerRecordRepository;
import io.github.batnam.loyalty.campaign.drawing.WinnerSelectionService;
import io.github.batnam.loyalty.campaign.config.CampaignProperties;
import io.github.batnam.loyalty.campaign.select.SelectionStrategy;
import io.github.batnam.loyalty.campaign.select.WinnerSelection;
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
    @Autowired CampaignProperties props;
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

    private int recordWeightedEntry(long drawingId, long memberId, String idempotencyKey, int weight, StringBuilder out) {
        return postJson("/drawings/" + drawingId + "/entries",
                Map.of("memberId", memberId, "sagaId", 99, "idempotencyKey", idempotencyKey, "weight", weight),
                Map.of(), out);
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

    /**
     * DRAWING-004 — entry de-duplication is keyed on {@code idempotency_key} ONLY, NOT on member.
     *
     * <p>NOTE ON THE TASK PREMISE: the requested scenario assumed a per-Drawing {@code allow_multiple_entries}
     * flag enforced by a {@code UNIQUE(drawing_id, member_id)} constraint. No such flag or constraint exists
     * in loyalty-campaign — the only uniqueness on {@code drawing_entry} is {@code idempotency_key} (V1
     * baseline, line 72; schema comment: "a Saga replay cannot enter a Member twice"). By design the same
     * Member MAY hold many entries (each its own key) — more entries = more chances under SEEDED_RNG/WEIGHTED.
     * This test therefore pins the ACTUAL contract rather than asserting a rejection the code never makes:
     * a distinct key for the same member creates a NEW row (201); an exact-key replay is the idempotent no-op.
     */
    @Test
    void sameMemberWithDistinctKeysCreatesDistinctEntriesWhileSameKeyReplays() {
        long campaign = liveCampaign();
        long drawingId = drawing(campaign, "SEEDED_RNG", 1, true, Instant.now().plus(1, ChronoUnit.DAYS));

        StringBuilder first = new StringBuilder();
        assertThat(recordEntry(drawingId, 77L, "m77-key-a", first)).isEqualTo(201);
        long firstEntry = json(first).path("entryId").asLong();

        // SAME member, DIFFERENT key -> a second distinct entry is accepted (NOT rejected): 201, new row.
        StringBuilder second = new StringBuilder();
        assertThat(recordEntry(drawingId, 77L, "m77-key-b", second)).isEqualTo(201);
        assertThat(json(second).path("entryId").asLong()).isNotEqualTo(firstEntry);

        // SAME member, SAME key as the first -> idempotent replay: 200, the original row.
        StringBuilder replay = new StringBuilder();
        assertThat(recordEntry(drawingId, 77L, "m77-key-a", replay)).isEqualTo(200);
        assertThat(json(replay).path("entryId").asLong()).isEqualTo(firstEntry);
    }

    /**
     * DRAWING-005 — WEIGHTED selection (complements the SEEDED_RNG and FIRST_N coverage). K winners are
     * selected without replacement, the seed is auditable ({@code seed_hex} recorded, 64 hex chars), and the
     * outcome is deterministically re-verifiable from {@code seed + secret + frozen entry order/weights} by
     * re-running the pure {@link WinnerSelection} algorithm with the same inputs.
     */
    @Test
    void weightedDrawingSelectsKWinnersWithAuditableReplayableSeed() {
        long campaign = liveCampaign();
        long drawingId = drawing(campaign, "WEIGHTED", 3, true, Instant.now().minus(1, ChronoUnit.MINUTES));
        // Six entries with ascending weights; entry-id order is arrival order here.
        for (int i = 0; i < 6; i++) {
            assertThat(recordWeightedEntry(drawingId, 500L + i, "wt-" + drawingId + "-" + i, i + 1, new StringBuilder()))
                    .isEqualTo(201);
        }

        winnerSelection.selectWinner(drawingId);

        var drawn = drawings.findById(drawingId).orElseThrow();
        assertThat(drawn.getStatus()).isEqualTo(DrawingStatus.CLOSED);
        var winners = winnerRecords.findByDrawingIdOrderByWinnerIndexAsc(drawingId);
        assertThat(winners).hasSize(3);
        assertThat(winners).extracting(w -> w.getWinnerIndex()).doesNotHaveDuplicates();
        assertThat(winners).extracting(w -> w.getMemberId()).doesNotHaveDuplicates();
        assertThat(winners).allSatisfy(w -> assertThat(w.getSeedHex()).hasSize(64));   // replayable seed
        assertThat(outboxFor("DrawingCompleted", drawingId)).hasSize(1);
        assertThat(outboxFor("WinnerSelected", drawingId)).hasSize(3);

        // Re-verify deterministically: feed seed+secret+frozen entry order/weights back into the pure
        // algorithm; an auditor reproduces the exact same winners (member + winner_index).
        List<WinnerSelection.Entry> pool = java.util.stream.IntStream.range(0, 6)
                .mapToObj(i -> new WinnerSelection.Entry(500L + i, i, i + 1)).toList();
        List<WinnerSelection.Winner> replay = WinnerSelection.select(drawingId, drawn.getDrawAt(),
                props.selection().hmacSecret(), pool, SelectionStrategy.WEIGHTED, 3);
        // winner_record rows are read ordered by winner_index; the replay preserves the draw's pick order, so
        // the two agree as sets (the same winners), and crucially each replayed winner_index maps to the same
        // member_id as the persisted row — the audit reproduction.
        assertThat(replay).extracting(WinnerSelection.Winner::winnerIndex)
                .containsExactlyInAnyOrderElementsOf(winners.stream().map(w -> w.getWinnerIndex()).toList());
        Map<Integer, Long> persistedByIndex = winners.stream()
                .collect(java.util.stream.Collectors.toMap(w -> w.getWinnerIndex(), w -> w.getMemberId()));
        assertThat(replay).allSatisfy(w ->
                assertThat(w.memberId()).isEqualTo(persistedByIndex.get(w.winnerIndex())));
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
