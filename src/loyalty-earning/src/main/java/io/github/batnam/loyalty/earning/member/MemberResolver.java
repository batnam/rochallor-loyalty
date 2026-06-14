package io.github.batnam.loyalty.earning.member;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.Optional;

/**
 * Resolves a customer-scoped EarnEvent to a loyalty-core Member (L3 §3.2: "resolution and per-Program
 * fan-out happen HERE"). Anti-corruption boundary — a thin REST call to core's {@code /members/lookup};
 * earning never reads the member table directly. A 404 means the customer isn't enrolled, so the event
 * is silently skipped (not an error).
 */
@Component
public class MemberResolver {

    private static final Logger log = LoggerFactory.getLogger(MemberResolver.class);

    private final RestClient core;

    public MemberResolver(RestClient coreRestClient) {
        this.core = coreRestClient;
    }

    public Optional<MemberRef> resolve(long programId, long customerId) {
        try {
            MemberRef ref = core.get()
                    .uri(uri -> uri.path("/members/lookup")
                            .queryParam("programId", programId)
                            .queryParam("customerId", customerId)
                            .build())
                    .retrieve()
                    .body(MemberRef.class);
            return Optional.ofNullable(ref);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                log.debug("no member for programId={} customerId={} — skipping", programId, customerId);
                return Optional.empty();
            }
            throw e;
        }
    }
}
