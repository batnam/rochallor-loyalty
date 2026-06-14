package io.github.batnam.loyalty.redemption.consume;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.batnam.loyalty.redemption.fulfil.PartnerOutcome;
import io.github.batnam.loyalty.redemption.saga.RedemptionOrchestrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Resume Consumer (L3 §3.3) — drains {@code loyalty.fulfillment.resume.v1} and finishes the parked Saga
 * for the partner-dependent 3rd-Party Voucher path. Stateless; it correlates the event's
 * {@code externalRef} to the saga and hands the partner outcome to the Orchestrator, which is idempotent
 * (a duplicate webhook on a terminal saga is a silent no-op).
 */
@Component
public class ResumeConsumer {

    private static final Logger log = LoggerFactory.getLogger(ResumeConsumer.class);

    private final RedemptionOrchestrator orchestrator;
    private final ObjectMapper mapper;

    public ResumeConsumer(RedemptionOrchestrator orchestrator, ObjectMapper appObjectMapper) {
        this.orchestrator = orchestrator;
        this.mapper = appObjectMapper;
    }

    @KafkaListener(topics = "${redemption.topics.fulfillment-resume}",
            containerFactory = "kafkaListenerContainerFactory")
    public void onResume(String message) {
        ResumeEvent event;
        try {
            event = mapper.readValue(message, ResumeEvent.class);
        } catch (Exception e) {
            log.error("unparseable resume event, skipping: {}", message, e);
            return;   // poison message — don't block the partition (DLQ wiring is infra, out of scope)
        }
        log.debug("resume externalRef={} outcome={}", event.externalRef(), event.outcome());
        orchestrator.resume(event.externalRef(),
                new PartnerOutcome(event.isSuccess(), event.externalRef(), event.payload()));
    }
}
