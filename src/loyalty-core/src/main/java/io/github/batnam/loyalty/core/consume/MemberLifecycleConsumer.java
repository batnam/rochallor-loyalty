package io.github.batnam.loyalty.core.consume;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.batnam.loyalty.core.member.MembershipAggregate;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code loyalty.member.lifecycle.v1} produced by {@code loyalty-integration-bridge} and
 * drives the Member State Machine (L3 §3.3). v1 contract carries one {@code lifecycleType}:
 * {@code CUSTOMER_CLOSED} → close every Member the Customer holds. Keyed on {@code customerId}
 * (a Member may not exist for an unenrolled Customer — that close is a silent no-op).
 */
@Component
public class MemberLifecycleConsumer {

    private static final Logger log = LoggerFactory.getLogger(MemberLifecycleConsumer.class);

    private final ObjectMapper mapper;
    private final MembershipAggregate membership;

    public MemberLifecycleConsumer(ObjectMapper mapper, MembershipAggregate membership) {
        this.mapper = mapper;
        this.membership = membership;
    }

    @KafkaListener(topics = "${core.topics.member-lifecycle}")
    public void onLifecycle(ConsumerRecord<String, String> record) throws Exception {
        JsonNode node = mapper.readTree(record.value());
        String lifecycleType = node.path("lifecycleType").asText();
        long customerId = node.path("customerId").asLong();

        if ("CUSTOMER_CLOSED".equals(lifecycleType)) {
            membership.closeForCustomer(customerId, "CUSTOMER_CLOSED");
        } else {
            log.warn("ignoring unsupported lifecycleType={} (customerId={})", lifecycleType, customerId);
        }
    }
}
