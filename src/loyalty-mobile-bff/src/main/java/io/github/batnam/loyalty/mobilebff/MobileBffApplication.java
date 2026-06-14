package io.github.batnam.loyalty.mobilebff;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * loyalty-mobile-bff — the customer-facing edge API (C4 L2 BFFs; {@code loyalty-mobile-bff.yaml}).
 *
 * <p>An <strong>aggregation-only</strong> service: it owns no datastore and produces/consumes no Kafka.
 * Behind AWS API Gateway it fans out to {@code loyalty-core} (membership, balance, tier, ledger),
 * {@code loyalty-redemption} (reward catalogue + two-phase redemption), and {@code loyalty-campaign}
 * (live campaigns + sweepstakes entries), composing their responses for the RDB Mobile App. The BFF does
 * no token handling: the gateway Authentication Service verifies the customer token and injects the CIF,
 * so the member identity ({@code memberId}/{@code customerId}) arrives in the request; v1 maps
 * {@code customerId} 1:1 to {@code memberId}.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class MobileBffApplication {
    public static void main(String[] args) {
        SpringApplication.run(MobileBffApplication.class, args);
    }
}
