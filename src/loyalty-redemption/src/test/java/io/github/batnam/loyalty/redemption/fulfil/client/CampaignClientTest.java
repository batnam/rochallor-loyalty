package io.github.batnam.loyalty.redemption.fulfil.client;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the T-13 entry seam to {@code loyalty-campaign}'s shipped contract: the request body carries
 * {@code memberId, sagaId, idempotencyKey, weight} and the {@code entryId} from the {@code DrawingEntry}
 * response becomes the adapter's external ref. Standalone WireMock — no Spring context, no Docker.
 */
class CampaignClientTest {

    private final WireMockServer stub = new WireMockServer(options().dynamicPort());

    @BeforeEach
    void start() {
        stub.start();
    }

    @AfterEach
    void stop() {
        stub.stop();
    }

    private CampaignClient client() {
        // Pin HTTP/1.1 to match RestClientConfig — the JDK client otherwise negotiates HTTP/2, which
        // WireMock rejects with RST_STREAM.
        HttpClient httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        RestClient http = RestClient.builder()
                .baseUrl(stub.baseUrl())
                .requestFactory(new JdkClientHttpRequestFactory(httpClient))
                .build();
        return new CampaignClient(http);
    }

    @Test
    void sendsTheFullEntryContractAndReturnsEntryId() {
        stub.stubFor(post(urlEqualTo("/drawings/77/entries"))
                .willReturn(okJson("{\"entryId\":9001,\"drawingId\":77,\"memberId\":42,\"weight\":null}")
                        .withStatus(201)));

        String ref = client().recordEntry(77L, 42L, 555L, "saga-555-drawing-77", null);

        assertThat(ref).isEqualTo("9001");
        stub.verify(postRequestedFor(urlEqualTo("/drawings/77/entries"))
                .withRequestBody(equalToJson(
                        "{\"memberId\":42,\"sagaId\":555,\"idempotencyKey\":\"saga-555-drawing-77\",\"weight\":null}")));
    }

    @Test
    void forwardsWeightForWeightedDrawings() {
        stub.stubFor(post(urlEqualTo("/drawings/88/entries"))
                .willReturn(okJson("{\"entryId\":12}")));

        client().recordEntry(88L, 7L, 33L, "saga-33-drawing-88", 5);

        stub.verify(postRequestedFor(urlEqualTo("/drawings/88/entries"))
                .withRequestBody(equalToJson("{\"weight\":5}", true, true)));
    }
}
