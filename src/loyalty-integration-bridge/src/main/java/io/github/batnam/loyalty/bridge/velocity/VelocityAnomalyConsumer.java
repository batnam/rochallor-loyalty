package io.github.batnam.loyalty.bridge.velocity;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.batnam.loyalty.bridge.canonical.EarnEvent;
import io.github.batnam.loyalty.bridge.canonical.FraudAlert;
import io.github.batnam.loyalty.bridge.config.BridgeTopics;
import io.github.batnam.loyalty.bridge.config.VelocityProperties;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cross-cutting fraud detector. Tails the Bridge's own
 * {@code loyalty.earn.translated.v1} output (its own consumer group) and raises an
 * {@code EARN_VELOCITY_SPIKE} on {@code loyalty.fraud.alert.v1} when a customer's earn count within
 * the sliding window crosses the configured threshold. Hysteresis: one alert per crossing, reset
 * when the count falls back below the threshold — avoids per-event alert floods.
 *
 * <p>Degraded-availability: on cold start the in-memory window is rebuilt by replaying the earn
 * stream; during rebuild, alerts may lag. The hot earn/ledger path is unaffected.
 */
@Component
public class VelocityAnomalyConsumer {

    private static final Logger log = LoggerFactory.getLogger(VelocityAnomalyConsumer.class);
    private static final String ANOMALY_TYPE = "EARN_VELOCITY_SPIKE";
    private static final String EVENT_TYPE = "loyalty.fraud.alert.v1";
    private static final String[] PROPAGATED_HEADERS = {"traceparent", "source"};

    private final VelocityProperties cfg;
    private final SlidingWindowCounterStore store;
    private final ObjectMapper mapper;
    private final KafkaTemplate<String, String> kafka;
    private final BridgeTopics topics;
    private final Set<Long> alerted = ConcurrentHashMap.newKeySet();

    public VelocityAnomalyConsumer(VelocityProperties cfg,
                                   SlidingWindowCounterStore store,
                                   ObjectMapper mapper,
                                   KafkaTemplate<String, String> kafka,
                                   BridgeTopics topics) {
        this.cfg = cfg;
        this.store = store;
        this.mapper = mapper;
        this.kafka = kafka;
        this.topics = topics;
    }

    @KafkaListener(
            topics = "${bridge.topics.earn-translated}",
            groupId = "${spring.application.name}-velocity")
    public void onEarn(ConsumerRecord<String, String> record) throws Exception {
        if (!cfg.enabled()) {
            return;
        }
        EarnEvent earn = mapper.readValue(record.value(), EarnEvent.class);
        if (earn.customerId() == null) {
            return;
        }
        Instant now = Instant.now();
        Instant eventAt = earn.occurredAt() != null ? earn.occurredAt() : now;

        int count = store.recordAndCount(
                earn.customerId(), eventAt, Duration.ofDays(cfg.windowDays()), now);

        if (count > cfg.maxEarnsPerWindow()) {
            if (alerted.add(earn.customerId())) {   // first crossing only
                emitAlert(earn.customerId(), count, now, record);
            }
        } else {
            alerted.remove(earn.customerId());       // reset once back below threshold
        }
    }

    private void emitAlert(long customerId, int count, Instant now, ConsumerRecord<String, String> trigger)
            throws Exception {
        FraudAlert alert = new FraudAlert(
                "velocity-spike:" + customerId + ":" + now.toEpochMilli(),
                EVENT_TYPE,
                now,
                1,
                customerId,
                ANOMALY_TYPE,
                cfg.windowDays(),
                (double) count,
                (double) cfg.maxEarnsPerWindow(),
                now);

        ProducerRecord<String, String> out = new ProducerRecord<>(
                topics.fraudAlert(),
                String.valueOf(customerId),
                mapper.writeValueAsString(alert));
        for (String name : PROPAGATED_HEADERS) {
            Header h = trigger.headers().lastHeader(name);
            if (h != null) {
                out.headers().add(name, h.value());
            }
        }
        kafka.send(out);
        log.warn("EARN_VELOCITY_SPIKE customerId={} count={} > threshold={} over {}d",
                customerId, count, cfg.maxEarnsPerWindow(), cfg.windowDays());
    }
}
