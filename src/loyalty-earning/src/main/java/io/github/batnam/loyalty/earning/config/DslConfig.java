package io.github.batnam.loyalty.earning.config;

import io.github.batnam.loyalty.earning.dsl.DslInterpreter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Exposes the pure {@link DslInterpreter} (now a framework-free type in the {@code :domain} module,
 * ) as a Spring bean for the Rule Engine and the dry-run evaluator to inject. The domain
 * stays free of {@code @Component}; the wiring lives here in the app ring.
 */
@Configuration
public class DslConfig {

    @Bean
    public DslInterpreter dslInterpreter() {
        return new DslInterpreter();
    }
}
