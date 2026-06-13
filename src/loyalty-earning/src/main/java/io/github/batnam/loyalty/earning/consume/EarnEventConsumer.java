package io.github.batnam.loyalty.earning.consume;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.batnam.loyalty.earning.config.EarningProperties;
import io.github.batnam.loyalty.earning.engine.RuleEngine;
import io.github.batnam.loyalty.earning.member.MemberRef;
import io.github.batnam.loyalty.earning.member.MemberResolver;
import io.github.batnam.loyalty.earning.replay.EarnEventLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * The Earn Event Consumer (L3 §4 component 2) — the hot-path entry point. For each translated
 * {@code loyalty.earn.translated.v1} message it: persists the event to the replay store (for dry-run),
 * resolves the customer to a Member (per-Program fan-out is 1:1 in v1), and drives the Rule Engine.
 *
 * <p>Runs in a transaction so the replay-store write and the engine's work commit together; the
 * idempotency gate makes redelivery safe (ack-mode RECORD → a thrown exception re-delivers).
 * Unresolved / inactive members are skipped (not errors).
 */
@Component
public class EarnEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(EarnEventConsumer.class);

    private final ObjectMapper mapper;
    private final EarnEventLogRepository replayStore;
    private final MemberResolver memberResolver;
    private final RuleEngine engine;
    private final long defaultProgramId;

    public EarnEventConsumer(ObjectMapper mapper, EarnEventLogRepository replayStore,
                             MemberResolver memberResolver, RuleEngine engine, EarningProperties props) {
        this.mapper = mapper;
        this.replayStore = replayStore;
        this.memberResolver = memberResolver;
        this.engine = engine;
        this.defaultProgramId = props.defaultProgramId();
    }

    @KafkaListener(topics = "${earning.topics.earn-translated}", containerFactory = "kafkaListenerContainerFactory")
    @Transactional
    public void onEarnEvent(String message) throws Exception {
        EarnEvent event = mapper.readValue(message, EarnEvent.class);

        // Replay store: persist the translated event (idempotent on event_id) for dry-run.
        replayStore.insertIgnore(event.eventId(), event.source(), event.customerId(),
                mapper.writeValueAsString(event.payload()), event.occurredAt());

        // Resolve customer -> Member (v1: single Program, 1:1).
        Optional<MemberRef> member = memberResolver.resolve(defaultProgramId, event.customerId());
        if (member.isEmpty()) {
            log.debug("eventId={} customerId={} not enrolled — skipping", event.eventId(), event.customerId());
            return;
        }
        if (!member.get().isActive()) {
            log.debug("eventId={} member={} not ACTIVE — skipping", event.eventId(), member.get().memberId());
            return;
        }

        engine.process(event, member.get());
    }
}
