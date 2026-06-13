package io.github.batnam.loyalty.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.batnam.loyalty.core.approval.ApprovalRequest;
import io.github.batnam.loyalty.core.approval.ApprovalRequestStore;
import io.github.batnam.loyalty.core.cohort.CohortRepository;
import io.github.batnam.loyalty.core.cohort.PointCohort;
import io.github.batnam.loyalty.core.error.CoreException;
import io.github.batnam.loyalty.core.job.ProgramExpiryProcessor;
import io.github.batnam.loyalty.core.ledger.AppendResult;
import io.github.batnam.loyalty.core.ledger.EntryType;
import io.github.batnam.loyalty.core.ledger.LedgerRepository;
import io.github.batnam.loyalty.core.ledger.LedgerService;
import io.github.batnam.loyalty.core.member.Member;
import io.github.batnam.loyalty.core.member.MemberRepository;
import io.github.batnam.loyalty.core.member.MemberStatus;
import io.github.batnam.loyalty.core.member.MembershipAggregate;
import io.github.batnam.loyalty.core.domain.reservation.Reservation;
import io.github.batnam.loyalty.core.reservation.ReservationManager;
import jakarta.persistence.EntityManager;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.testcontainers.DockerClientFactory.instance;

/**
 * End-to-end loyalty-core test against real Postgres + Kafka (Testcontainers). Exercises the Ledger
 * write path, the two-phase reservation + FIFO cohort consumption, the append-only DB guard, the
 * member-lifecycle consumer, the transactional outbox relay, tier recompute, and the expiry job.
 * Skipped when Docker is absent (mirrors the bridge's IT).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class CoreIntegrationTest {

    private static final long PROGRAM = 1L;

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    @Container
    static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @BeforeAll
    static void requireDocker() {
        assumeTrue(instance().isDockerAvailable(), "Docker not available — skipping Testcontainers IT");
    }

    @Autowired LedgerService ledger;
    @Autowired ReservationManager reservations;
    @Autowired MembershipAggregate membership;
    @Autowired ApprovalRequestStore approvals;
    @Autowired ProgramExpiryProcessor expiry;
    @Autowired MemberRepository members;
    @Autowired LedgerRepository ledgerRepo;
    @Autowired CohortRepository cohorts;
    @Autowired KafkaTemplate<String, String> template;
    @Autowired ObjectMapper mapper;
    @Autowired EntityManager em;

    private long newMember(long customerId) {
        return membership.optIn(PROGRAM, customerId, 1).getMemberId();
    }

    @Test
    void earnIsIdempotentAndUpdatesBalance() {
        long m = newMember(2001);
        AppendResult first = ledger.appendEarn(m, PROGRAM, "earn:2001:a", 120, 120, "CARD_SPEND", "USD", Instant.now());
        AppendResult replay = ledger.appendEarn(m, PROGRAM, "earn:2001:a", 120, 120, "CARD_SPEND", "USD", Instant.now());

        assertThat(first.replayed()).isFalse();
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.entry().entryId()).isEqualTo(first.entry().entryId());
        assertThat(members.findById(m).orElseThrow().getRedeemableBalance()).isEqualTo(120);
        assertThat(ledgerRepo.findBySourceRefAndEntryType("earn:2001:a", EntryType.Earned)).isPresent();
    }

    @Test
    void reserveThenCommitConsumesCohortsFifo() {
        long m = newMember(2002);
        ledger.appendEarn(m, PROGRAM, "earn:2002:a", 0, 100, "CARD_SPEND", "USD", Instant.now().minusSeconds(60));
        ledger.appendEarn(m, PROGRAM, "earn:2002:b", 0, 200, "CARD_SPEND", "USD", Instant.now());

        Reservation held = reservations.reserve(m, PROGRAM, 150, 9L, null, "idem:2002:1");
        reservations.commit(held.reservationId(), "disbursement-xyz");

        assertThat(members.findById(m).orElseThrow().getRedeemableBalance()).isEqualTo(150);
        List<PointCohort> ordered = cohorts.findByMemberIdAndProgramIdOrderByEarnedAtAsc(m, PROGRAM);
        assertThat(ordered.get(0).getConsumedAmount()).isEqualTo(100);   // oldest fully consumed
        assertThat(ordered.get(1).getConsumedAmount()).isEqualTo(50);    // remainder from next
        assertThat(ledgerRepo.findBySourceRefAndEntryType("reservation-" + held.reservationId(), EntryType.Redeemed)).isPresent();
    }

    @Test
    void reserveGateRejectsOverspend() {
        long m = newMember(2003);
        ledger.appendEarn(m, PROGRAM, "earn:2003:a", 0, 50, "CARD_SPEND", "USD", Instant.now());
        assertThatThrownBy(() -> reservations.reserve(m, PROGRAM, 100, null, null, "idem:2003:1"))
                .isInstanceOf(CoreException.class)
                .matches(e -> ((CoreException) e).code().equals("BALANCE_INSUFFICIENT"));
    }

    @Test
    void releaseRestoresHoldWithoutLedgerEntry() {
        long m = newMember(2004);
        ledger.appendEarn(m, PROGRAM, "earn:2004:a", 0, 100, "CARD_SPEND", "USD", Instant.now());
        Reservation held = reservations.reserve(m, PROGRAM, 80, null, null, "idem:2004:1");
        reservations.release(held.reservationId(), "adapter failure");

        // Balance untouched (a HELD reservation never decrements it), and the freed amount is reservable again.
        assertThat(members.findById(m).orElseThrow().getRedeemableBalance()).isEqualTo(100);
        assertThat(ledgerRepo.findBySourceRefAndEntryType("reservation-" + held.reservationId(), EntryType.Redeemed)).isEmpty();
        reservations.reserve(m, PROGRAM, 100, null, null, "idem:2004:2");   // would throw if hold still counted
    }

    @Test
    @Transactional
    void pointLedgerIsAppendOnlyAtDbLayer() {
        long m = newMember(2005);
        var entry = ledger.appendEarn(m, PROGRAM, "earn:2005:a", 0, 10, "CARD_SPEND", "USD", Instant.now()).entry();
        assertThatThrownBy(() -> {
            em.createNativeQuery("UPDATE point_ledger SET reason = 'tamper' WHERE entry_id = :id")
                    .setParameter("id", entry.entryId())
                    .executeUpdate();
            em.flush();
        }).hasStackTraceContaining("append-only");
    }

    @Test
    void tierRecomputesOnQualifyingDelta() {
        long m = newMember(2006);
        ledger.appendEarn(m, PROGRAM, "earn:2006:a", 50_000, 50_000, "CARD_SPEND", "USD", Instant.now());
        assertThat(members.findById(m).orElseThrow().getCurrentTierCode()).isEqualTo("SILVER");
    }

    @Test
    void approvalConfirmWritesAdjustedEntry() {
        long m = newMember(2007);
        ApprovalRequest req = approvals.create("cs-rep-1", m, PROGRAM, 0L, 500L, "goodwill credit");
        approvals.confirm("cs-supervisor-1", req.getApprovalRequestId(), "BEP-APV-777");

        assertThat(members.findById(m).orElseThrow().getRedeemableBalance()).isEqualTo(500);
        assertThat(ledgerRepo.findBySourceRefAndEntryType("approval-" + req.getApprovalRequestId(), EntryType.Adjusted)).isPresent();
    }

    @Test
    void expiryJobExpiresUnconsumedCohort() {
        long m = newMember(2008);
        // earned 25 months ago; seed Program expiry is 24 months -> cohort is past expiry now.
        Instant longAgo = Instant.now().minus(25L * 30, ChronoUnit.DAYS);
        ledger.appendEarn(m, PROGRAM, "earn:2008:a", 70, 70, "CARD_SPEND", "USD", longAgo);
        assertThat(members.findById(m).orElseThrow().getRedeemableBalance()).isEqualTo(70);

        int expired = expiry.expireProgram(PROGRAM, Instant.now());
        assertThat(expired).isGreaterThanOrEqualTo(1);
        assertThat(members.findById(m).orElseThrow().getRedeemableBalance()).isEqualTo(0);
        assertThat(ledgerRepo.findBySourceRefAndEntryType(
                "expiry-cohort-" + cohorts.findByMemberIdAndProgramIdOrderByEarnedAtAsc(m, PROGRAM).get(0).getCohortId(),
                EntryType.Expired)).isPresent();
    }

    @Test
    void customerClosedLifecycleClosesMember() {
        long customerId = 2009;
        long m = newMember(customerId);
        String body = """
                {"eventId":"it:lc:1","eventType":"loyalty.member.lifecycle.v1",
                 "occurredAt":"2026-05-29T10:30:00Z","schemaVersion":1,
                 "customerId":%d,"lifecycleType":"CUSTOMER_CLOSED"}""".formatted(customerId);
        template.send("loyalty.member.lifecycle.v1", String.valueOf(customerId), body);

        awaitMemberStatus(m, MemberStatus.CLOSED);
    }

    @Test
    void outboxRelayPublishesLedgerEvent() throws Exception {
        long m = newMember(2010);
        ledger.appendEarn(m, PROGRAM, "earn:2010:a", 0, 33, "CARD_SPEND", "USD", Instant.now());
        JsonNode event = awaitEventContaining("loyalty.ledger.v1", "earn:2010:a");
        assertThat(event.path("eventType").asText()).isEqualTo("PointsEarned");
        assertThat(event.path("memberId").asLong()).isEqualTo(m);
        assertThat(event.path("redeemableDelta").asLong()).isEqualTo(33);
    }

    // --- helpers -------------------------------------------------------------

    private void awaitMemberStatus(long memberId, MemberStatus expected) {
        long deadline = System.currentTimeMillis() + 25_000;
        while (System.currentTimeMillis() < deadline) {
            Member m = members.findById(memberId).orElseThrow();
            if (m.getStatus() == expected) return;
            sleep();
        }
        throw new AssertionError("member " + memberId + " never reached " + expected);
    }

    private JsonNode awaitEventContaining(String topic, String marker) throws Exception {
        try (Consumer<String, String> consumer = newConsumer(topic)) {
            long deadline = System.currentTimeMillis() + 25_000;
            while (System.currentTimeMillis() < deadline) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> r : records) {
                    if (r.value() != null && r.value().contains(marker)) {
                        return mapper.readTree(r.value());
                    }
                }
            }
        }
        throw new AssertionError("no event containing '" + marker + "' on " + topic);
    }

    private Consumer<String, String> newConsumer(String topic) {
        Map<String, Object> props =
                KafkaTestUtils.consumerProps(KAFKA.getBootstrapServers(), "it-" + topic, "true");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        Consumer<String, String> consumer = new DefaultKafkaConsumerFactory<>(
                props, new StringDeserializer(), new StringDeserializer()).createConsumer();
        consumer.subscribe(List.of(topic));
        return consumer;
    }

    private static void sleep() {
        try {
            Thread.sleep(300);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
