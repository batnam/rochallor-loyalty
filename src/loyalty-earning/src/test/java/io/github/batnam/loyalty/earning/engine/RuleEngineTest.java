package io.github.batnam.loyalty.earning.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.batnam.loyalty.earning.caps.CapService;
import io.github.batnam.loyalty.earning.config.EarningProperties;
import io.github.batnam.loyalty.earning.consume.EarnEvent;
import io.github.batnam.loyalty.earning.dsl.DslInterpreter;
import io.github.batnam.loyalty.earning.dsl.DslParser;
import io.github.batnam.loyalty.earning.ledger.LedgerClient;
import io.github.batnam.loyalty.earning.ledger.LedgerEarnRequest;
import io.github.batnam.loyalty.earning.member.MemberRef;
import io.github.batnam.loyalty.earning.outbox.OutboxRelay;
import io.github.batnam.loyalty.earning.rule.ActiveRule;
import io.github.batnam.loyalty.earning.rule.Rules;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the {@link RuleEngine} orchestration with mocked I/O collaborators (idempotency,
 * the {@link Rules} port, caps, ledger, outbox). Uses the REAL {@link DslInterpreter} (it is a pure
 * function — no reason to mock the rule semantics). Pins the hot-path contract: idempotency
 * short-circuit, multi-rule sum (one Ledger entry per matching rule), and cap-drop.
 *
 * <p>Since ADR-0001 the engine loads rules through the {@code Rules} port already parsed, so the test
 * builds {@link ActiveRule}s by parsing the DSL fixtures here (mirroring what the JPA adapter does)
 * rather than mocking JPA entities + an {@code ObjectMapper}.
 */
class RuleEngineTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long PROGRAM = 1L;
    private static final long MEMBER = 42L;

    private final IdempotencyRepository idem = mock(IdempotencyRepository.class);
    private final Rules rules = mock(Rules.class);
    private final CapService caps = mock(CapService.class);
    private final LedgerClient ledger = mock(LedgerClient.class);
    private final OutboxRelay outbox = mock(OutboxRelay.class);

    private final EarningProperties props = new EarningProperties(
            new EarningProperties.Topics("loyalty.earn.translated.v1", "loyalty.earning.points_earned.v1"),
            PROGRAM, new EarningProperties.Core("http://core"),
            new EarningProperties.Outbox(100), new EarningProperties.CapPurge("0 0 3 * * *"));

    private final RuleEngine engine = new RuleEngine(idem, rules, new DslInterpreter(),
            caps, ledger, outbox, props);

    private static final MemberRef MEMBER_REF = new MemberRef(MEMBER, PROGRAM, "ACTIVE");

    private EarnEvent event(String id) {
        return new EarnEvent(id, "loyalty.earn.translated.v1", Instant.parse("2026-05-30T10:00:00Z"),
                1, 999L, "CARD_SPEND", Map.of("amount", 25000, "currency", "USD"));
    }

    private ActiveRule rule(long ruleId, String dsl) {
        try {
            JsonNode node = MAPPER.readTree(dsl);
            return new ActiveRule(ruleId, DslParser.parse(node));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static final String RATE_RULE = """
        {"dslVersion":1,"earnSource":"CARD_SPEND",
         "rows":[{"when":{"amount":"*"},"earn":{"type":"RATE","perAmount":10000,"points":1}}]}""";

    @Test
    void happyPathWritesLedgerEntryEnqueuesEventAndRecordsIdempotency() {
        List<ActiveRule> active = List.of(rule(7L, RATE_RULE));
        when(idem.existsById("evt-1")).thenReturn(false);
        when(rules.findActiveForSource(eq(PROGRAM), eq("CARD_SPEND"), any())).thenReturn(active);
        when(caps.tryConsume(anyLong(), anyLong(), anyLong(), any(), anyLong(), any())).thenReturn(true);
        when(ledger.appendEarned(any(), anyString())).thenReturn(100L);

        EarnResult result = engine.process(event("evt-1"), MEMBER_REF);

        assertThat(result.replayed()).isFalse();
        assertThat(result.entryIds()).containsExactly(100L);
        assertThat(result.totalRedeemableDelta()).isEqualTo(2);   // 25000/10000 floored

        ArgumentCaptor<LedgerEarnRequest> req = ArgumentCaptor.forClass(LedgerEarnRequest.class);
        verify(ledger).appendEarned(req.capture(), eq("evt-1:7"));
        assertThat(req.getValue().memberId()).isEqualTo(MEMBER);
        assertThat(req.getValue().sourceRef()).isEqualTo("evt-1:7");
        assertThat(req.getValue().redeemableDelta()).isEqualTo(2);
        assertThat(req.getValue().currency()).isEqualTo("USD");

        verify(outbox).enqueue(anyString(), eq("PointsEarned"), anyString(), eq(String.valueOf(MEMBER)), any());
        verify(idem).save(any(IdempotencyKey.class));
    }

    @Test
    void replayShortCircuitsBeforeAnyRuleWork() {
        when(idem.existsById("evt-dup")).thenReturn(true);

        EarnResult result = engine.process(event("evt-dup"), MEMBER_REF);

        assertThat(result.replayed()).isTrue();
        verify(rules, never()).findActiveForSource(anyLong(), anyString(), any());
        verify(ledger, never()).appendEarned(any(), anyString());
        verify(outbox, never()).enqueue(anyString(), anyString(), anyString(), anyString(), any());
        verify(idem, never()).save(any());
    }

    @Test
    void everyMatchingRuleFiresItsOwnLedgerEntryAndTotalsSum() {
        String fixed50 = """
            {"dslVersion":1,"earnSource":"CARD_SPEND",
             "rows":[{"when":{"amount":"*"},"earn":{"type":"FIXED","points":50}}]}""";
        List<ActiveRule> active = List.of(rule(7L, RATE_RULE), rule(8L, fixed50));
        when(idem.existsById("evt-2")).thenReturn(false);
        when(rules.findActiveForSource(eq(PROGRAM), eq("CARD_SPEND"), any())).thenReturn(active);
        when(caps.tryConsume(anyLong(), anyLong(), anyLong(), any(), anyLong(), any())).thenReturn(true);
        when(ledger.appendEarned(any(), eq("evt-2:7"))).thenReturn(100L);
        when(ledger.appendEarned(any(), eq("evt-2:8"))).thenReturn(101L);

        EarnResult result = engine.process(event("evt-2"), MEMBER_REF);

        assertThat(result.entryIds()).containsExactly(100L, 101L);
        assertThat(result.totalRedeemableDelta()).isEqualTo(52);   // 2 + 50
    }

    @Test
    void capExhaustionDropsTheFireButStillRecordsIdempotency() {
        List<ActiveRule> active = List.of(rule(7L, RATE_RULE));
        when(idem.existsById("evt-3")).thenReturn(false);
        when(rules.findActiveForSource(eq(PROGRAM), eq("CARD_SPEND"), any())).thenReturn(active);
        when(caps.tryConsume(anyLong(), anyLong(), anyLong(), any(), anyLong(), any())).thenReturn(false);

        EarnResult result = engine.process(event("evt-3"), MEMBER_REF);

        assertThat(result.entryIds()).isEmpty();
        verify(ledger, never()).appendEarned(any(), anyString());
        verify(outbox, never()).enqueue(anyString(), anyString(), anyString(), anyString(), any());
        verify(idem).save(any(IdempotencyKey.class));   // event WAS processed — replay must short-circuit next time
    }
}
