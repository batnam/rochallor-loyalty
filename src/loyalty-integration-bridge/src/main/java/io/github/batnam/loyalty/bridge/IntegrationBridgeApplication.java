package io.github.batnam.loyalty.bridge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * loyalty-integration-bridge — the ingress gateway (ADR-0025).
 *
 * <p>It validates Loyalty-authored ingress events ({@code loyalty.ingress.*}) against their JSON
 * Schema, stamps the canonical {@code source}, and re-emits the internal canonical event
 * ({@code loyalty.earn.translated.v1}). Stateless, no RDS (ADR-0015).
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class IntegrationBridgeApplication {
    public static void main(String[] args) {
        SpringApplication.run(IntegrationBridgeApplication.class, args);
    }
}
