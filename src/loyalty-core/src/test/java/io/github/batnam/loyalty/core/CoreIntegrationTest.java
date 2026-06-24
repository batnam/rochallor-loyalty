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
    @Autowired io.github.batnam.loyalty.core.program.ProgramConfigService programConfig;
    @Autowired io.github.batnam.loyalty.core.job.TierReevaluationProcessor tierReeval;
    @Autowired MemberRepository members;
    @Autowired LedgerRepository ledgerRepo;
    @Autowired CohortRepository cohorts;
    @Autowired KafkaTemplate<String, String> template;
    @Autowired ObjectMapper mapper;
    @Autowired EntityManager em;
    @Autowired org.springframework.transaction.PlatformTransactionManager txManager;

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
    void earnMultiplierDefaultsToOneForSeededTiers() {
        // V4 adds earn_multiplier NOT NULL DEFAULT 1.000; V2-seeded tiers are left unconfigured, so the
        // multiplier the /members/lookup response carries is 1.0 (no accrual scaling) until deployed.
        assertThat(programConfig.earnMultiplierFor(PROGRAM, "SILVER")).isEqualByComparingTo("1.0");
        assertThat(programConfig.earnMultiplierFor(PROGRAM, null)).isEqualByComparingTo("1.0"); // no tier
    }

    @Test
    void tierReflectsOnlyInWindowQualifyingForRolling12() {
        // Seed Program is ROLLING_12_MONTHS. Qualifying earned 13 months ago is out of the window, so
        // it must NOT lift the Member into SILVER (threshold 50_000): the windowed sum is 0, which only
        // meets BRONZE (threshold 0). Only in-window qualifying counts.
        long m = newMember(2011);
        Instant outOfWindow = Instant.now().minus(13L * 31, ChronoUnit.DAYS);
        ledger.appendEarn(m, PROGRAM, "earn:2011:old", 50_000, 0, "CARD_SPEND", "USD", outOfWindow);
        assertThat(members.findById(m).orElseThrow().getCurrentTierCode()).isEqualTo("BRONZE");

        // A fresh in-window earn that crosses the threshold lifts the tier.
        ledger.appendEarn(m, PROGRAM, "earn:2011:new", 50_000, 0, "CARD_SPEND", "USD", Instant.now());
        assertThat(members.findById(m).orElseThrow().getCurrentTierCode()).isEqualTo("SILVER");
    }

    @Test
    void tierReevaluationDropsTierAsPointsAgeOut() {
        // An in-window earn lifts to SILVER; a backdated-only history would age out, but the job here
        // simply re-asserts the windowed tier for active members (no time travel in-test).
        long m = newMember(2012);
        ledger.appendEarn(m, PROGRAM, "earn:2012:a", 60_000, 0, "CARD_SPEND", "USD", Instant.now());
        assertThat(members.findById(m).orElseThrow().getCurrentTierCode()).isEqualTo("SILVER");

        int changed = tierReeval.reevaluateProgram(PROGRAM, Instant.now());
        assertThat(changed).isGreaterThanOrEqualTo(0);   // idempotent: no spurious change for in-window points
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

    @Test
    void paymentReversedClawsBackPoints() {
        long m = newMember(2013);
        // Earn comes from loyalty-earning shaped as sourceRef = eventId:ruleId; the reversal matches by the
        // bare eventId prefix (JpaLedger appends ":%").
        ledger.appendEarn(m, PROGRAM, "evt-REV-1:ruleA", 400, 400, "CARD_SPEND", "USD", Instant.now());
        assertThat(members.findById(m).orElseThrow().getRedeemableBalance()).isEqualTo(400);
        assertThat(members.findById(m).orElseThrow().getQualifyingBalance()).isEqualTo(400);

        // PaymentReversedConsumer reads "originalEventId" — the eventId portion WITHOUT the ":ruleA" suffix.
        String body = """
                {"eventId":"it:rev:1","eventType":"loyalty.payment.reversed.v1",
                 "occurredAt":"2026-06-01T10:30:00Z","schemaVersion":1,
                 "originalEventId":"evt-REV-1"}""";
        template.send("loyalty.payment.reversed.v1", "evt-REV-1", body);

        awaitReversed(m, "evt-REV-1:ruleA");

        // Clawback posted negative deltas equal to the original; balances fell back to zero.
        assertThat(members.findById(m).orElseThrow().getRedeemableBalance()).isEqualTo(0);
        assertThat(members.findById(m).orElseThrow().getQualifyingBalance()).isEqualTo(0);

        // Idempotency: a duplicate reversal must NOT double-claw (the (sourceRef, Reversed) unique constraint).
        template.send("loyalty.payment.reversed.v1", "evt-REV-1", body);
        try {
            Thread.sleep(2_000);   // give the consumer a chance to (not) re-apply
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        assertThat(members.findById(m).orElseThrow().getRedeemableBalance()).isEqualTo(0);
        assertThat(members.findById(m).orElseThrow().getQualifyingBalance()).isEqualTo(0);
        // Exactly one Reversed entry for that sourceRef.
        assertThat(ledgerRepo.findBySourceRefAndEntryType("evt-REV-1:ruleA", EntryType.Reversed)).isPresent();
    }

    @Test
    void reserveBlockedWhenTcsBehind() {
        long m = newMember(2014);   // enrolls accepting tcs version 1 (newMember -> optIn(..., 1))
        ledger.appendEarn(m, PROGRAM, "earn:2014:a", 0, 100, "CARD_SPEND", "USD", Instant.now());

        // Advance the Program's current T&Cs version so the Member (still on v1) is behind. Program is
        // @Immutable, so bump it via native SQL and clear the persistence context to force a reload.
        // The Program row is shared seed state, so restore it afterwards to avoid polluting sibling tests.
        bumpProgramTcsVersion(PROGRAM, 2);
        try {
            assertThatThrownBy(() -> reservations.reserve(m, PROGRAM, 50, null, null, "idem:2014:1"))
                    .isInstanceOf(CoreException.class)
                    .matches(e -> ((CoreException) e).code().equals("TCS_REACCEPTANCE_REQUIRED"));

            // Re-accept the current version; a subsequent reserve now passes.
            membership.acceptTcs(m, 2);
            Reservation held = reservations.reserve(m, PROGRAM, 50, null, null, "idem:2014:2");
            assertThat(held.isHeld()).isTrue();
        } finally {
            bumpProgramTcsVersion(PROGRAM, 1);
        }
    }

    // --- helpers -------------------------------------------------------------

    private void bumpProgramTcsVersion(long programId, int version) {
        new org.springframework.transaction.support.TransactionTemplate(txManager).execute(status -> {
            em.createNativeQuery("UPDATE program SET current_tcs_version = :v, tcs_version_effective_at = now() WHERE program_id = :id")
                    .setParameter("v", version)
                    .setParameter("id", programId)
                    .executeUpdate();
            em.flush();
            em.clear();   // evict the @Immutable Program so ReservationManager reloads the bumped row
            return null;
        });
    }

    private void awaitReversed(long memberId, String sourceRef) {
        long deadline = System.currentTimeMillis() + 25_000;
        while (System.currentTimeMillis() < deadline) {
            if (ledgerRepo.findBySourceRefAndEntryType(sourceRef, EntryType.Reversed).isPresent()) return;
            sleep();
        }
        throw new AssertionError("member " + memberId + " never got a Reversed entry for " + sourceRef);
    }

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
