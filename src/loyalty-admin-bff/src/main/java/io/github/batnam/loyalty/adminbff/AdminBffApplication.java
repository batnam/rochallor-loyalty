package io.github.batnam.loyalty.adminbff;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * loyalty-admin-bff — the Bank Employee Portal (BEP) edge API (C4 L2 BFFs; {@code loyalty-admin-bff.yaml}).
 *
 * <p>An <strong>aggregation-only</strong> service: it owns no datastore and produces/consumes no Kafka.
 * Behind AWS API Gateway it fans out to {@code loyalty-core} (member admin + approval requests),
 * {@code loyalty-earning} (earn sources + rule authoring), {@code loyalty-redemption} (reward catalogue),
 * {@code loyalty-campaign} (campaign/drawing admin), and {@code loyalty-integration-bridge} (fraud
 * alerts), composing their responses for the BEP. The BFF does no token handling: the gateway
 * Authentication Service verifies the employee token and injects the employee identity + Loyalty roles as
 * request parameters ({@code userId}, {@code roles}); this BFF reads them and applies per-operation role
 * gating (Arch §7.1).
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class AdminBffApplication {
    public static void main(String[] args) {
        SpringApplication.run(AdminBffApplication.class, args);
    }
}
