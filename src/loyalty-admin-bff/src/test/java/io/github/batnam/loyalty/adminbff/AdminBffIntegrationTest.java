package io.github.batnam.loyalty.adminbff;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.patch;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end loyalty-admin-bff test: the BFF owns no datastore, so the only integration surface is the
 * outbound REST it aggregates. One WireMock server stands in for loyalty-core + loyalty-earning +
 * loyalty-redemption + loyalty-campaign + loyalty-integration-bridge (distinct paths, no conflict).
 * Exercises role-gating off the gateway-injected identity headers (403 without the role, 200/201 with
 * it), aggregation pass-through, X-Actor forwarding for the audit trail, upstream-error translation
 * (409 MISSING_APPROVAL), and the 401 when the identity header is absent. No Docker needed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminBffIntegrationTest {

    static final WireMockServer STUBS = new WireMockServer(options().dynamicPort());

    static {
        STUBS.start();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("admin-bff.core.base-url", STUBS::baseUrl);
        registry.add("admin-bff.earning.base-url", STUBS::baseUrl);
        registry.add("admin-bff.redemption.base-url", STUBS::baseUrl);
        registry.add("admin-bff.campaign.base-url", STUBS::baseUrl);
        registry.add("admin-bff.bridge.base-url", STUBS::baseUrl);
    }

    @Autowired ObjectMapper mapper;
    @Autowired Environment env;

    private RestClient http;

    /**
     * The employee identity the gateway Authentication Service injects after verifying the token — the BFF
     * does no token handling, it just reads the {@code userId} + {@code roles} request parameters.
     */
    private record Actor(String userId, String roles) {
    }

    private static Actor actor(String userId, String... roles) {
        return new Actor(userId, String.join(",", roles));
    }

    /** Append the gateway-injected actor (userId + roles) as query parameters. */
    private static String withActor(String uri, Actor actor) {
        if (actor == null) {
            return uri;
        }
        String sep = uri.contains("?") ? "&" : "?";
        return uri + sep + "userId=" + actor.userId() + "&roles=" + actor.roles();
    }

    @BeforeEach
    void setUp() {
        http = RestClient.create("http://localhost:" + env.getProperty("local.server.port"));
        STUBS.resetAll();
    }

    private int httpGet(String uri, Actor actor, StringBuilder out) {
        return http.get().uri(withActor(uri, actor))
                .exchange((req, res) -> {
                    out.append(new String(res.getBody().readAllBytes()));
                    return res.getStatusCode().value();
                });
    }

    private int postJson(String uri, Object body, Actor actor, StringBuilder out) {
        return http.post().uri(withActor(uri, actor)).contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange((req, res) -> {
                    out.append(new String(res.getBody().readAllBytes()));
                    return res.getStatusCode().value();
                });
    }

    private int patchJson(String uri, Object body, Actor actor, StringBuilder out) {
        return http.patch().uri(withActor(uri, actor)).contentType(MediaType.APPLICATION_JSON)
                .body(body)
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

    // --- tests ---------------------------------------------------------------

    @Test
    void findMembersAggregatesFromCoreForCsRole() {
        STUBS.stubFor(get(urlPathEqualTo("/members")).willReturn(okJson("""
                [{"memberId":42,"customerId":1001,"createdAt":"2026-01-01T00:00:00Z"}]""")));

        StringBuilder body = new StringBuilder();
        int status = httpGet("/members?customerId=1001", actor("cs-1", "loyalty-cs-maker"), body);

        assertThat(status).isEqualTo(200);
        assertThat(json(body).get(0).path("memberId").asLong()).isEqualTo(42);
    }

    @Test
    void createRuleRequiresCampaignManagerRole() {
        StringBuilder denied = new StringBuilder();
        int deniedStatus = postJson("/programs/1/rules", Map.of("earnSourceId", 5, "dslJson", Map.of()),
                actor("ro-1", "loyalty-readonly"), denied);
        assertThat(deniedStatus).isEqualTo(403);
        assertThat(json(denied).path("code").asText()).isEqualTo("FORBIDDEN");

        STUBS.stubFor(post(urlEqualTo("/programs/1/rules")).willReturn(okJson("""
                {"programId":1,"programCode":"RDB_REWARDS","ruleId":7,"earnSourceId":5,"dslJson":{},
                 "version":1,"status":"DRAFT"}""").withStatus(201)));

        StringBuilder created = new StringBuilder();
        int createdStatus = postJson("/programs/1/rules", Map.of("earnSourceId", 5, "dslJson", Map.of()),
                actor("mgr-1", "loyalty-campaign-manager"), created);

        assertThat(createdStatus).isEqualTo(201);
        assertThat(json(created).path("status").asText()).isEqualTo("DRAFT");
        // X-Actor (the employee user id) is forwarded to earning for the audit trail.
        STUBS.verify(postRequestedFor(urlEqualTo("/programs/1/rules")).withHeader("X-Actor", equalTo("mgr-1")));
    }

    @Test
    void raiseApprovalRequestForwardsToCoreWithActor() {
        STUBS.stubFor(post(urlEqualTo("/approval-requests")).willReturn(okJson("""
                {"requestId":900,"type":"ADJUSTMENT","payload":{},"status":"PENDING",
                 "requestedBy":"cs-1","createdAt":"2026-05-31T00:00:00Z"}""").withStatus(201)));

        StringBuilder body = new StringBuilder();
        int status = postJson("/approval-requests",
                Map.of("type", "ADJUSTMENT", "payload", Map.of("memberId", 42, "redeemableDelta", 1000)),
                actor("cs-1", "loyalty-cs-maker"), body);

        assertThat(status).isEqualTo(201);
        assertThat(json(body).path("status").asText()).isEqualTo("PENDING");
        STUBS.verify(postRequestedFor(urlEqualTo("/approval-requests")).withHeader("X-Actor", equalTo("cs-1")));
    }

    @Test
    void rewardActivationWithoutApprovalIsTranslated409() {
        STUBS.stubFor(patch(urlPathMatching("/rewards/\\d+")).willReturn(aResponse().withStatus(409)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"code\":\"MISSING_APPROVAL\",\"detail\":\"activation requires a BEP approval ref\"}")));

        StringBuilder body = new StringBuilder();
        int status = patchJson("/rewards/5", Map.of("status", "ACTIVE"),
                actor("mgr-1", "loyalty-campaign-manager"), body);

        assertThat(status).isEqualTo(409);
        assertThat(json(body).path("code").asText()).isEqualTo("MISSING_APPROVAL");
    }

    @Test
    void fraudAlertsRequireFraudOpsRole() {
        StringBuilder denied = new StringBuilder();
        assertThat(httpGet("/fraud/alerts", actor("mgr-1", "loyalty-campaign-manager"), denied)).isEqualTo(403);

        STUBS.stubFor(get(urlPathEqualTo("/fraud/alerts")).willReturn(okJson("""
                {"items":[{"programId":1,"programCode":"RDB_REWARDS","memberId":42,
                  "anomalyType":"EARN_VELOCITY_SPIKE","observedRate":12.5,"threshold":5.0,
                  "detectedAt":"2026-05-31T00:00:00Z"}],"nextCursor":null}""")));

        StringBuilder ok = new StringBuilder();
        int status = httpGet("/fraud/alerts", actor("fraud-1", "loyalty-fraud-ops"), ok);

        assertThat(status).isEqualTo(200);
        assertThat(json(ok).path("items").get(0).path("anomalyType").asText()).isEqualTo("EARN_VELOCITY_SPIKE");
    }

    @Test
    void adminRoleIsAWildcardOverFunctionalRoles() {
        STUBS.stubFor(get(urlPathEqualTo("/fraud/alerts")).willReturn(okJson(
                "{\"items\":[],\"nextCursor\":null}")));

        StringBuilder body = new StringBuilder();
        // loyalty-admin holds no functional role explicitly, yet passes the fraud-ops gate.
        int status = httpGet("/fraud/alerts", actor("admin-1", "loyalty-admin"), body);

        assertThat(status).isEqualTo(200);
    }

    @Test
    void missingIdentityHeaderIs401() {
        StringBuilder body = new StringBuilder();
        int status = httpGet("/members?customerId=1001", null, body);

        assertThat(status).isEqualTo(401);
        assertThat(json(body).path("code").asText()).isEqualTo("UNAUTHORIZED");
    }
}
