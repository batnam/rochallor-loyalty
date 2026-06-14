package io.github.batnam.loyalty.redemption.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

/**
 * The four outbound {@link RestClient}s redemption needs (Anti-Corruption boundaries — redemption is a
 * client of these systems, never sharing their data stores):
 * <ul>
 *   <li>{@code coreRestClient} — loyalty-core Reservation API (reserve / commit / release).</li>
 *   <li>{@code paymentHubRestClient} — Payment Hub disbursement (CashbackAdapter).</li>
 *   <li>{@code voucherPartnerRestClient} — 3rd-party voucher provisioning (ThirdPartyVoucherAdapter).</li>
 *   <li>{@code campaignRestClient} — loyalty-campaign Drawing.recordEntry (SweepstakesAdapter).</li>
 * </ul>
 * Each is pinned to HTTP/1.1 — the JDK client otherwise negotiates HTTP/2, which is flaky against some
 * servers (and the WireMock stubs used in tests). mTLS is provided by cluster infra (out of scope here,
 * so the clients are plain HTTP for local/test).
 */
@Configuration
public class RestClientConfig {

    private static RestClient http11(String baseUrl) {
        HttpClient httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(new JdkClientHttpRequestFactory(httpClient))
                .build();
    }

    @Bean
    public RestClient coreRestClient(RedemptionProperties props) {
        return http11(props.core().baseUrl());
    }

    @Bean
    public RestClient paymentHubRestClient(RedemptionProperties props) {
        return http11(props.paymentHub().baseUrl());
    }

    @Bean
    public RestClient voucherPartnerRestClient(RedemptionProperties props) {
        return http11(props.voucherPartner().baseUrl());
    }

    @Bean
    public RestClient campaignRestClient(RedemptionProperties props) {
        return http11(props.campaign().baseUrl());
    }
}
