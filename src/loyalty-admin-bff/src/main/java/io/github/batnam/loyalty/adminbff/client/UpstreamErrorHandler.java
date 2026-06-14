package io.github.batnam.loyalty.adminbff.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.batnam.loyalty.adminbff.error.BffException;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Translates an upstream service's error response into a {@link BffException}, preserving the upstream
 * HTTP status and its RFC-7807 {@code code} (e.g. {@code CAP_EXCEEDED}, {@code DSL_INVALID},
 * {@code MISSING_APPROVAL}) so the BEP surfaces the real cause instead of a blanket 500. A non-Problem
 * body degrades to a generic {@code UPSTREAM_ERROR} carrying the same status. Registered on every
 * outbound {@link RestClient} via {@code defaultStatusHandler(HttpStatusCode::isError, ...)}.
 */
@Component
public class UpstreamErrorHandler implements RestClient.ResponseSpec.ErrorHandler {

    private final ObjectMapper mapper;

    public UpstreamErrorHandler(ObjectMapper appObjectMapper) {
        this.mapper = appObjectMapper;
    }

    @Override
    public void handle(HttpRequest request, ClientHttpResponse response) throws IOException {
        HttpStatusCode status = response.getStatusCode();
        String body = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
        String code = "UPSTREAM_ERROR";
        String detail = body;
        try {
            JsonNode problem = mapper.readTree(body);
            if (problem.hasNonNull("code")) {
                code = problem.get("code").asText();
            }
            if (problem.hasNonNull("detail")) {
                detail = problem.get("detail").asText();
            }
        } catch (Exception ignored) {
            // non-JSON upstream body — keep the raw body as detail.
        }
        throw new BffException(HttpStatus.valueOf(status.value()), code, detail);
    }
}
