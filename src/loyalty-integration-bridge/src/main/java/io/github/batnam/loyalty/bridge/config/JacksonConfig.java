package io.github.batnam.loyalty.bridge.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provides the **Jackson 2** ({@code com.fasterxml.jackson}) {@code ObjectMapper} the Bridge uses.
 * Spring Boot 4 auto-configures a Jackson 3 ({@code tools.jackson}) mapper, but networknt's
 * validator consumes Jackson 2 {@code JsonNode}, so the Bridge standardises on Jackson 2 for its
 * consume → validate → produce path. {@code Instant} is written as ISO-8601 (not epoch).
 */
@Configuration
public class JacksonConfig {

    @Bean
    public ObjectMapper appObjectMapper() {
        return JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
    }
}
