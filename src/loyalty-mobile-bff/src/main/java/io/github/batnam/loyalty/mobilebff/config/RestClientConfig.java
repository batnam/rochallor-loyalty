package io.github.batnam.loyalty.mobilebff.config;

import io.github.batnam.loyalty.mobilebff.client.UpstreamErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

/**
 * The three outbound {@link RestClient}s the mobile BFF aggregates (Anti-Corruption boundaries — the
 * BFF is a client of these services, never a sharer of their data stores):
 * <ul>
 *   <li>{@code coreRestClient} — loyalty-core: membership, balance, tier, ledger history.</li>
 *   <li>{@code redemptionRestClient} — loyalty-redemption: reward catalogue + two-phase redemption.</li>
 *   <li>{@code campaignRestClient} — loyalty-campaign: live campaigns + sweepstakes entries.</li>
 * </ul>
 * Each is pinned to HTTP/1.1 — the JDK client otherwise negotiates HTTP/2, which is flaky against the
 * WireMock stubs used in tests. mTLS is provided by cluster infra (out of scope here).
 */
@Configuration
public class RestClientConfig {

    private static RestClient http11(String baseUrl, UpstreamErrorHandler errorHandler) {
        HttpClient httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(new JdkClientHttpRequestFactory(httpClient))
                .defaultStatusHandler(HttpStatusCode::isError, errorHandler)
                .build();
    }

    @Bean
    public RestClient coreRestClient(MobileBffProperties props, UpstreamErrorHandler errorHandler) {
        return http11(props.core().baseUrl(), errorHandler);
    }

    @Bean
    public RestClient redemptionRestClient(MobileBffProperties props, UpstreamErrorHandler errorHandler) {
        return http11(props.redemption().baseUrl(), errorHandler);
    }

    @Bean
    public RestClient campaignRestClient(MobileBffProperties props, UpstreamErrorHandler errorHandler) {
        return http11(props.campaign().baseUrl(), errorHandler);
    }
}
