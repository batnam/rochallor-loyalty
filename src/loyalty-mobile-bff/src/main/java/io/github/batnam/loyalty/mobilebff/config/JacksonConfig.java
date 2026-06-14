package io.github.batnam.loyalty.mobilebff.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The <strong>Jackson 2</strong> ({@code com.fasterxml.jackson}) {@code ObjectMapper} the BFF uses to
 * read upstream responses. Spring Boot 4 auto-configures a Jackson 3
 * ({@code tools.jackson}) mapper; we standardise on Jackson 2 so the JSON we exchange with the backend
 * services is byte-compatible with the canonical platform shapes. {@code Instant} as ISO-8601.
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
