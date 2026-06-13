package io.github.batnam.loyalty.mobilebff;

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
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end loyalty-mobile-bff test: the BFF owns no datastore, so the only integration surface is the
 * outbound REST it aggregates. One WireMock server stands in for loyalty-core + loyalty-redemption +
 * loyalty-campaign (distinct paths, no conflict). The BFF does no token handling — the gateway
 * Authentication Service injects the verified identity — so requests carry the member identity directly
 * (reads take {@code customerId} from the request; redemption carries CIF + CASA). Exercises that, plus
 * aggregation pass-through, the sync/async redemption status mirror (200/202), and upstream-error
 * translation (409 with code). No Docker needed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MobileBffIntegrationTest {

    private static final long CUSTOMER = 42L;   // customerId (CIF) == memberId in v1 (1:1)
    private static final String ACCOUNT = "0011000123";   // target CASA supplied by the Mobile App
    private static final long PROGRAM = 1L;

    static final WireMockServer STUBS = new WireMockServer(options().dynamicPort());

    static {
        STUBS.start();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("mobile-bff.core.base-url", STUBS::baseUrl);
        registry.add("mobile-bff.redemption.base-url", STUBS::baseUrl);
        registry.add("mobile-bff.campaign.base-url", STUBS::baseUrl);
    }

    @Autowired ObjectMapper mapper;
    @Autowired Environment env;

    private RestClient http;

    @BeforeEach
    void setUp() {
        http = RestClient.create("http://localhost:" + env.getProperty("local.server.port"));
        STUBS.resetAll();
    }

    // No token handling at the BFF: the gateway Authentication Service verifies the token and injects the
    // member identity (CIF) into the request, so tests just supply customerId as a request param/body field.
    private int httpGet(String uri, StringBuilder out) {
        return http.get().uri(uri)
                .exchange((req, res) -> {
                    out.append(new String(res.getBody().readAllBytes()));
                    return res.getStatusCode().value();
                });
    }

    private int postJson(String uri, Object body, Map<String, String> headers, StringBuilder out) {
        return http.post().uri(uri).contentType(MediaType.APPLICATION_JSON)
                .headers(h -> headers.forEach(h::add))
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
    void listEnrolledProgramsTakesMemberFromRequestAndPassesThrough() {
        STUBS.stubFor(get(urlPathEqualTo("/members/42/programs")).willReturn(okJson("""
                [{"programId":1,"programCode":"RDB_REWARDS","optInStatus":"ACTIVE","tcsVersionAccepted":3,
                  "eligibilityStatus":"ELIGIBLE","redeemableBalance":12000,"qualifyingBalance":8000,
                  "tier":{"tierId":2,"name":"Gold"}}]""")));

        StringBuilder body = new StringBuilder();
        int status = httpGet("/me/programs?customerId=" + CUSTOMER, body);

        assertThat(status).isEqualTo(200);
        JsonNode programs = json(body).path("enrolledPrograms");
        assertThat(programs).hasSize(1);
        assertThat(programs.get(0).path("programCode").asText()).isEqualTo("RDB_REWARDS");
        assertThat(programs.get(0).path("tier").path("name").asText()).isEqualTo("Gold");
    }

    @Test
    void balanceAggregatesFromCore() {
        // The BFF reads core's /projection (core has no dedicated /balance) and shapes it into Balance.
        STUBS.stubFor(get(urlPathEqualTo("/members/42/programs/1/projection")).willReturn(okJson("""
                {"memberId":42,"programId":1,"redeemableBalance":12000,"qualifyingBalance":8000,
                 "tierCode":"SILVER","status":"ACTIVE","asOf":"2026-05-29T12:00:00Z"}""")));

        StringBuilder body = new StringBuilder();
        int status = httpGet("/me/programs/" + PROGRAM + "/balance?customerId=" + CUSTOMER, body);

        assertThat(status).isEqualTo(200);
        assertThat(json(body).path("redeemableBalance").asLong()).isEqualTo(12000);
        assertThat(json(body).path("qualifyingBalance").asLong()).isEqualTo(8000);
        // No held-reservation netting in the core projection, so effective == redeemable.
        assertThat(json(body).path("effectiveRedeemableBalance").asLong()).isEqualTo(12000);
    }

    @Test
    void syncRedemptionMirrors200AndMapsCommittedToCompleted() {
        STUBS.stubFor(post(urlEqualTo("/redemptions")).willReturn(okJson("""
                {"redemptionId":900,"programId":1,"programCode":"RDB_REWARDS","rewardId":5,
                 "status":"COMMITTED","externalRef":"PH-TXN-1"}""").withStatus(200)));

        StringBuilder body = new StringBuilder();
        int status = postJson("/redemptions", Map.of("customerId", CUSTOMER,
                "accountNumber", ACCOUNT, "programId", PROGRAM, "rewardId", 5),
                Map.of("Idempotency-Key", "k-1"), body);

        assertThat(status).isEqualTo(200);
        assertThat(json(body).path("status").asText()).isEqualTo("COMPLETED");
        // Idempotency-Key and the request-supplied bank+loyalty context (CIF + CASA) are forwarded verbatim.
        STUBS.verify(postRequestedFor(urlEqualTo("/redemptions"))
                .withHeader("Idempotency-Key", equalTo("k-1"))
                .withRequestBody(equalToJson(
                        "{\"memberId\":" + CUSTOMER + ",\"customerId\":" + CUSTOMER
                                + ",\"accountNumber\":\"" + ACCOUNT + "\"}", true, true)));
    }

    @Test
    void asyncRedemptionMirrors202AndMapsFulfilling() {
        STUBS.stubFor(post(urlEqualTo("/redemptions")).willReturn(okJson("""
                {"redemptionId":901,"programId":1,"programCode":"RDB_REWARDS","rewardId":6,
                 "status":"FULFILLING","externalRef":null}""").withStatus(202)));

        StringBuilder body = new StringBuilder();
        int status = postJson("/redemptions", Map.of("customerId", CUSTOMER,
                "accountNumber", ACCOUNT, "programId", PROGRAM, "rewardId", 6),
                Map.of("Idempotency-Key", "k-2"), body);

        assertThat(status).isEqualTo(202);
        assertThat(json(body).path("status").asText()).isEqualTo("FULFILLING");
    }

    @Test
    void upstreamConflictIsTranslatedWithCode() {
        STUBS.stubFor(post(urlEqualTo("/redemptions")).willReturn(aResponse().withStatus(409)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"code\":\"INSUFFICIENT_BALANCE\",\"detail\":\"not enough points\"}")));

        StringBuilder body = new StringBuilder();
        int status = postJson("/redemptions", Map.of("customerId", CUSTOMER,
                "accountNumber", ACCOUNT, "programId", PROGRAM, "rewardId", 7),
                Map.of("Idempotency-Key", "k-3"), body);

        assertThat(status).isEqualTo(409);
        assertThat(json(body).path("code").asText()).isEqualTo("INSUFFICIENT_BALANCE");
    }

    @Test
    void liveCampaignsAggregateFromCampaignService() {
        STUBS.stubFor(get(urlPathEqualTo("/programs/1/campaigns")).willReturn(okJson("""
                [{"programId":1,"programCode":"RDB_REWARDS","campaignId":10,"name":"Summer Bonus",
                  "startsAt":"2026-06-01T00:00:00Z","endsAt":"2026-06-30T00:00:00Z","status":"LIVE"}]""")));

        StringBuilder body = new StringBuilder();
        int status = httpGet("/me/programs/" + PROGRAM + "/campaigns", body);

        assertThat(status).isEqualTo(200);
        assertThat(json(body).get(0).path("name").asText()).isEqualTo("Summer Bonus");
    }
}
