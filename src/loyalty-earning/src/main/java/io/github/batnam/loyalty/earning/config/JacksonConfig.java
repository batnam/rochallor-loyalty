package io.github.batnam.loyalty.earning.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provides the **Jackson 2** ({@code com.fasterxml.jackson}) {@code ObjectMapper} earning uses to
 * deserialize incoming {@code EarnEvent}s and serialize outbox payloads. Spring Boot 4 auto-configures
 * a Jackson 3 ({@code tools.jackson}) mapper; we standardise on Jackson 2 so the JSON we read/emit is
 * byte-compatible with the canonical events the bridge + core already produce. {@code Instant} as
 * ISO-8601 (not epoch).
 */
@Configuration
public class JacksonConfig {

    @Bean
    public ObjectMapper appObjectMapper() {
        return JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
    }
}
