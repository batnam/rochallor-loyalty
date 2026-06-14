package io.github.batnam.loyalty.earning.dsl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the pure DSL Interpreter (C4 L3 §4 component 5 — "DSL Interpreter is a pure
 * function … no I/O, no side effects"). Drives the decision-table semantics of
 * {@code docs/dsl/schema/earning-rule.schema.json}: predicate operators, FIRST vs COLLECT hit
 * policy, RATE/FIXED earn, tier multiplier, rounding, per-event cap, and balance routing.
 *
 * <p>Tests parse a compact DSL JSON (so they exercise {@link DslParser} too) and evaluate it against
 * an event payload — exactly the input the hot path and dry-run both feed the interpreter.
 */
class DslInterpreterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DslInterpreter INTERP = new DslInterpreter();

    private EarnOutcome eval(String dslJson, Map<String, Object> payload, MemberContext ctx) {
        try {
            RuleDsl dsl = DslParser.parse(MAPPER.readTree(dslJson));
            return INTERP.evaluate(dsl, payload, ctx);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private EarnOutcome eval(String dslJson, Map<String, Object> payload) {
        return eval(dslJson, payload, MemberContext.none());
    }

    // --- RATE earn + rounding ------------------------------------------------

    @Test
    void rateFloorsByDefault() {
        String dsl = """
            {"dslVersion":1,"earnSource":"CARD_SPEND",
             "rows":[{"when":{"amount":"*"},"earn":{"type":"RATE","perAmount":10000,"points":1}}]}""";
        EarnOutcome out = eval(dsl, Map.of("amount", 24000));   // 2.4 -> FLOOR -> 2
        assertThat(out.qualifyingDelta()).isEqualTo(2);
        assertThat(out.redeemableDelta()).isEqualTo(2);
    }

    @Test
    void rateRoundHalfUp() {
        String dsl = """
            {"dslVersion":1,"earnSource":"CARD_SPEND","rounding":"ROUND",
             "rows":[{"when":{"amount":"*"},"earn":{"type":"RATE","perAmount":10000,"points":1}}]}""";
        assertThat(eval(dsl, Map.of("amount", 25000)).redeemableDelta()).isEqualTo(3);   // 2.5 -> 3
        assertThat(eval(dsl, Map.of("amount", 24000)).redeemableDelta()).isEqualTo(2);   // 2.4 -> 2
    }

    @Test
    void rateCeil() {
        String dsl = """
            {"dslVersion":1,"earnSource":"CARD_SPEND","rounding":"CEIL",
             "rows":[{"when":{"amount":"*"},"earn":{"type":"RATE","perAmount":10000,"points":1}}]}""";
        assertThat(eval(dsl, Map.of("amount", 21000)).redeemableDelta()).isEqualTo(3);   // 2.1 -> 3
    }

    // --- FIXED earn ----------------------------------------------------------

    @Test
    void fixedAwardsFlatPoints() {
        String dsl = """
            {"dslVersion":1,"earnSource":"TERM_DEPOSIT_OPENED",
             "rows":[{"when":{"amount":"*"},"earn":{"type":"FIXED","points":500}}]}""";
        EarnOutcome out = eval(dsl, Map.of("amount", 1));
        assertThat(out.qualifyingDelta()).isEqualTo(500);
        assertThat(out.redeemableDelta()).isEqualTo(500);
    }

    // --- predicate operators -------------------------------------------------

    @Test
    void equalityShorthandMatchesAndMisses() {
        String dsl = """
            {"dslVersion":1,"earnSource":"BILL_PAYMENT",
             "rows":[{"when":{"paymentType":"BILL_PAYMENT"},"earn":{"type":"FIXED","points":10}}]}""";
        assertThat(eval(dsl, Map.of("paymentType", "BILL_PAYMENT")).redeemableDelta()).isEqualTo(10);
        assertThat(eval(dsl, Map.of("paymentType", "P2P_TRANSFER")).redeemableDelta()).isZero();
    }

    @Test
    void operatorsNeInNinAndComparisons() {
        // ne
        assertThat(eval("""
            {"dslVersion":1,"earnSource":"S","rows":[{"when":{"t":{"ne":"X"}},"earn":{"type":"FIXED","points":1}}]}""",
                Map.of("t", "Y")).redeemableDelta()).isEqualTo(1);
        assertThat(eval("""
            {"dslVersion":1,"earnSource":"S","rows":[{"when":{"t":{"ne":"X"}},"earn":{"type":"FIXED","points":1}}]}""",
                Map.of("t", "X")).redeemableDelta()).isZero();
        // in / nin
        assertThat(eval("""
            {"dslVersion":1,"earnSource":"S","rows":[{"when":{"t":{"in":["A","B"]}},"earn":{"type":"FIXED","points":1}}]}""",
                Map.of("t", "B")).redeemableDelta()).isEqualTo(1);
        assertThat(eval("""
            {"dslVersion":1,"earnSource":"S","rows":[{"when":{"t":{"nin":["A","B"]}},"earn":{"type":"FIXED","points":1}}]}""",
                Map.of("t", "B")).redeemableDelta()).isZero();
        // gt / gte / lt / lte
        assertThat(eval("""
            {"dslVersion":1,"earnSource":"S","rows":[{"when":{"a":{"gt":100}},"earn":{"type":"FIXED","points":1}}]}""",
                Map.of("a", 101)).redeemableDelta()).isEqualTo(1);
        assertThat(eval("""
            {"dslVersion":1,"earnSource":"S","rows":[{"when":{"a":{"gte":100}},"earn":{"type":"FIXED","points":1}}]}""",
                Map.of("a", 100)).redeemableDelta()).isEqualTo(1);
        assertThat(eval("""
            {"dslVersion":1,"earnSource":"S","rows":[{"when":{"a":{"lt":100}},"earn":{"type":"FIXED","points":1}}]}""",
                Map.of("a", 100)).redeemableDelta()).isZero();
        assertThat(eval("""
            {"dslVersion":1,"earnSource":"S","rows":[{"when":{"a":{"lte":100}},"earn":{"type":"FIXED","points":1}}]}""",
                Map.of("a", 100)).redeemableDelta()).isEqualTo(1);
        // between (inclusive)
        assertThat(eval("""
            {"dslVersion":1,"earnSource":"S","rows":[{"when":{"a":{"between":[10,20]}},"earn":{"type":"FIXED","points":1}}]}""",
                Map.of("a", 20)).redeemableDelta()).isEqualTo(1);
        assertThat(eval("""
            {"dslVersion":1,"earnSource":"S","rows":[{"when":{"a":{"between":[10,20]}},"earn":{"type":"FIXED","points":1}}]}""",
                Map.of("a", 21)).redeemableDelta()).isZero();
    }

    @Test
    void multipleFieldsAreAnded() {
        String dsl = """
            {"dslVersion":1,"earnSource":"FUND_TRANSFER",
             "rows":[{"when":{"paymentType":"FUND_TRANSFER","amount":{"gte":1000}},"earn":{"type":"FIXED","points":5}}]}""";
        assertThat(eval(dsl, Map.of("paymentType", "FUND_TRANSFER", "amount", 1000)).redeemableDelta()).isEqualTo(5);
        assertThat(eval(dsl, Map.of("paymentType", "FUND_TRANSFER", "amount", 999)).redeemableDelta()).isZero();
    }

    @Test
    void absentFieldNeverMatchesNonWildcardCondition() {
        String dsl = """
            {"dslVersion":1,"earnSource":"S","rows":[{"when":{"mcc":"5411"},"earn":{"type":"FIXED","points":1}}]}""";
        assertThat(eval(dsl, Map.of("amount", 100)).redeemableDelta()).isZero();
    }

    // --- hit policy ----------------------------------------------------------

    @Test
    void firstPolicyStopsAtFirstMatch() {
        String dsl = """
            {"dslVersion":1,"earnSource":"S","hitPolicy":"FIRST","rows":[
              {"when":{"amount":{"gte":100}},"earn":{"type":"FIXED","points":50}},
              {"when":{"amount":"*"},"earn":{"type":"FIXED","points":1}}]}""";
        assertThat(eval(dsl, Map.of("amount", 100)).redeemableDelta()).isEqualTo(50);   // first wins, second skipped
    }

    @Test
    void collectPolicySumsAllMatches() {
        String dsl = """
            {"dslVersion":1,"earnSource":"S","hitPolicy":"COLLECT","rows":[
              {"when":{"amount":{"gte":100}},"earn":{"type":"FIXED","points":50}},
              {"when":{"amount":"*"},"earn":{"type":"FIXED","points":1}}]}""";
        assertThat(eval(dsl, Map.of("amount", 100)).redeemableDelta()).isEqualTo(51);   // 50 + 1
    }

    // --- tier multiplier + per-event cap + balances --------------------------

    @Test
    void tierMultiplierScalesBeforeRounding() {
        String dsl = """
            {"dslVersion":1,"earnSource":"S","tierMultiplier":true,
             "rows":[{"when":{"amount":"*"},"earn":{"type":"FIXED","points":100}}]}""";
        EarnOutcome out = eval(dsl, Map.of("amount", 1), new MemberContext("GOLD", 1.5));
        assertThat(out.redeemableDelta()).isEqualTo(150);
    }

    @Test
    void perEventMaxClampsTheAward() {
        String dsl = """
            {"dslVersion":1,"earnSource":"S",
             "rows":[{"when":{"amount":"*"},"earn":{"type":"FIXED","points":1000}}],
             "caps":{"perEventMax":500}}""";
        assertThat(eval(dsl, Map.of("amount", 1)).redeemableDelta()).isEqualTo(500);
    }

    @Test
    void balancesRouteToSelectedBalanceOnly() {
        String qualifyingOnly = """
            {"dslVersion":1,"earnSource":"S",
             "rows":[{"when":{"amount":"*"},"earn":{"type":"FIXED","points":300,"balances":["qualifying"]}}]}""";
        EarnOutcome q = eval(qualifyingOnly, Map.of("amount", 1));
        assertThat(q.qualifyingDelta()).isEqualTo(300);
        assertThat(q.redeemableDelta()).isZero();

        String redeemableOnly = """
            {"dslVersion":1,"earnSource":"S",
             "rows":[{"when":{"amount":"*"},"earn":{"type":"FIXED","points":300,"balances":["redeemable"]}}]}""";
        EarnOutcome r = eval(redeemableOnly, Map.of("amount", 1));
        assertThat(r.qualifyingDelta()).isZero();
        assertThat(r.redeemableDelta()).isEqualTo(300);
    }

    @Test
    void noMatchYieldsZeroOutcome() {
        String dsl = """
            {"dslVersion":1,"earnSource":"S","rows":[{"when":{"paymentType":"NOPE"},"earn":{"type":"FIXED","points":1}}]}""";
        EarnOutcome out = eval(dsl, Map.of("paymentType", "OTHER"));
        assertThat(out.isZero()).isTrue();
    }
}
