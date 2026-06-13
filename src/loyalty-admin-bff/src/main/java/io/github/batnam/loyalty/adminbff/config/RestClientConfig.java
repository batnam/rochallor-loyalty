package io.github.batnam.loyalty.adminbff.config;

import io.github.batnam.loyalty.adminbff.client.UpstreamErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

/**
 * The five outbound {@link RestClient}s the admin BFF aggregates (Anti-Corruption boundaries — the BFF
 * is a client of these services, never a sharer of their data stores):
 * <ul>
 *   <li>{@code coreRestClient} — loyalty-core: member admin, ledger audit, approval requests.</li>
 *   <li>{@code earningRestClient} — loyalty-earning: earn sources + Earning Rule authoring.</li>
 *   <li>{@code redemptionRestClient} — loyalty-redemption: Reward Type catalogue + Reward authoring.</li>
 *   <li>{@code campaignRestClient} — loyalty-campaign: campaign + drawing admin.</li>
 *   <li>{@code bridgeRestClient} — loyalty-integration-bridge: velocity-anomaly fraud alerts.</li>
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
    public RestClient coreRestClient(AdminBffProperties props, UpstreamErrorHandler errorHandler) {
        return http11(props.core().baseUrl(), errorHandler);
    }

    @Bean
    public RestClient earningRestClient(AdminBffProperties props, UpstreamErrorHandler errorHandler) {
        return http11(props.earning().baseUrl(), errorHandler);
    }

    @Bean
    public RestClient redemptionRestClient(AdminBffProperties props, UpstreamErrorHandler errorHandler) {
        return http11(props.redemption().baseUrl(), errorHandler);
    }

    @Bean
    public RestClient campaignRestClient(AdminBffProperties props, UpstreamErrorHandler errorHandler) {
        return http11(props.campaign().baseUrl(), errorHandler);
    }

    @Bean
    public RestClient bridgeRestClient(AdminBffProperties props, UpstreamErrorHandler errorHandler) {
        return http11(props.bridge().baseUrl(), errorHandler);
    }
}
