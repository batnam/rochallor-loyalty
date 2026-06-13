package io.github.batnam.loyalty.redemption.config;

import io.github.batnam.loyalty.redemption.elig.EligibilityEngine;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the framework-free {@code :domain} types that have no {@code @Component} of their own (ADR-0001)
 * as Spring beans for the app ring to inject. Today that is the pure {@link EligibilityEngine}; the
 * Saga decider is static, so it needs no bean.
 */
@Configuration
public class DomainConfig {

    @Bean
    public EligibilityEngine eligibilityEngine() {
        return new EligibilityEngine();
    }
}
