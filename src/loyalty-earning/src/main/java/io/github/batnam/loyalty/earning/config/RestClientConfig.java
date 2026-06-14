package io.github.batnam.loyalty.earning.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

/**
 * The {@link RestClient} the Ledger Client and Member Resolver use to call loyalty-core's internal
 * API (Anti-Corruption boundary — earning is a client of core, never touches {@code point_ledger}).
 * Base URL is {@code earning.core.base-url}; mTLS is provided by cluster infra (out of scope here, so
 * the client is plain HTTP for local/test).
 */
@Configuration
public class RestClientConfig {

    @Bean
    public RestClient coreRestClient(EarningProperties props) {
        // Force HTTP/1.1 — the JDK client otherwise negotiates HTTP/2, which is flaky against some
        // servers (and the WireMock stub used in tests). core's internal API is plain REST.
        HttpClient httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        return RestClient.builder()
                .baseUrl(props.core().baseUrl())
                .requestFactory(new JdkClientHttpRequestFactory(httpClient))
                .build();
    }
}
