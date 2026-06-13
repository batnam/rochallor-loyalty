package io.github.batnam.loyalty.bridge.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.batnam.loyalty.bridge.canonical.FulfillmentResume;
import io.github.batnam.loyalty.bridge.config.BridgeTopics;
import io.github.batnam.loyalty.bridge.translate.VoucherWebhookTranslator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Set;

/**
 * HTTPS ingress for 3rd-party voucher partners (ADR-0025 cross-cutting). Verifies the HMAC
 * signature (DD-2), translates the body to {@code loyalty.fulfillment.resume.v1}, and produces it
 * for {@code loyalty-redemption} to resume the saga. The raw body is used as-is for HMAC
 * (no re-serialization).
 */
@RestController
@RequestMapping("/webhooks/voucher")
public class VoucherWebhookController {

    private static final Logger log = LoggerFactory.getLogger(VoucherWebhookController.class);
    private static final Set<String> VALID_STATUS = Set.of("READY", "FAILED");

    private final HmacVerifier verifier;
    private final ObjectMapper mapper;
    private final KafkaTemplate<String, String> kafka;
    private final BridgeTopics topics;

    public VoucherWebhookController(HmacVerifier verifier,
                                    ObjectMapper mapper,
                                    KafkaTemplate<String, String> kafka,
                                    BridgeTopics topics) {
        this.verifier = verifier;
        this.mapper = mapper;
        this.kafka = kafka;
        this.topics = topics;
    }

    @PostMapping
    public ResponseEntity<Void> receive(
            @RequestHeader("X-Signature") String signature,
            @RequestHeader("X-Timestamp") long timestamp,
            @RequestBody String rawBody) throws Exception {

        if (!verifier.verify(signature, timestamp, rawBody, Instant.now())) {
            log.warn("voucher webhook rejected: bad signature or stale timestamp");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        VoucherWebhookRequest req;
        try {
            req = mapper.readValue(rawBody, VoucherWebhookRequest.class);
        } catch (Exception parseError) {
            return ResponseEntity.badRequest().build();
        }
        if (req.jobHandle() == null || req.jobHandle().isBlank() || !VALID_STATUS.contains(req.status())) {
            return ResponseEntity.badRequest().build();
        }

        FulfillmentResume resume = VoucherWebhookTranslator.translate(req, Instant.now());
        kafka.send(topics.fulfillmentResume(), resume.externalRef(), mapper.writeValueAsString(resume));
        log.debug("voucher webhook → fulfillment.resume externalRef={} outcome={}", resume.externalRef(), resume.outcome());

        return ResponseEntity.accepted().build();
    }
}
