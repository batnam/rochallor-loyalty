package io.github.batnam.loyalty.adminbff.client;

import io.github.batnam.loyalty.adminbff.api.dto.AdminDtos.FraudAlertPage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Anti-Corruption boundary to loyalty-integration-bridge for velocity-anomaly fraud alerts.
 *
 * <p>Fraud detection lives as the Velocity-Anomaly consumer inside the bridge (Arch §4; there is no
 * separate {@code loyalty-fraud} container). The bridge consumes {@code loyalty.fraud.alert.v1} and
 * exposes the alerts as a read API; the admin BFF aggregates that read for the Fraud-Ops UI rather than
 * consuming Kafka itself, keeping the BFF aggregation-only.
 */
@Component
public class FraudClient {

    private final RestClient bridge;

    public FraudClient(@Qualifier("bridgeRestClient") RestClient bridgeRestClient) {
        this.bridge = bridgeRestClient;
    }

    public FraudAlertPage listAlerts(Long programId, String cursor, Integer limit) {
        return bridge.get()
                .uri(uri -> {
                    uri.path("/fraud/alerts");
                    if (programId != null) {
                        uri.queryParam("programId", programId);
                    }
                    if (cursor != null) {
                        uri.queryParam("cursor", cursor);
                    }
                    if (limit != null) {
                        uri.queryParam("limit", limit);
                    }
                    return uri.build();
                })
                .retrieve()
                .body(FraudAlertPage.class);
    }
}
