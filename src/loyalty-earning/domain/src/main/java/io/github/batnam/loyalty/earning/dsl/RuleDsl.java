package io.github.batnam.loyalty.earning.dsl;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Typed, validated representation of an Earning Rule's {@code dsl_json}
 * (earning-rule.schema.json). Immutable; produced by {@link DslParser} and consumed by
 * {@link DslInterpreter}. A constrained decision table — a list of {@link Row}s, each a field→
 * {@link Condition} predicate plus an {@link Earn} formula.
 */
public record RuleDsl(
        int dslVersion,
        String earnSource,
        HitPolicy hitPolicy,
        boolean tierMultiplier,
        Rounding rounding,
        List<Row> rows,
        Caps caps
) {
    public enum HitPolicy { FIRST, COLLECT }

    public enum Rounding { FLOOR, ROUND, CEIL }

    public enum EarnType { RATE, FIXED }

    public enum Balance { qualifying, redeemable }

    /** One decision-table row: a predicate (fields ANDed) and the earn to apply when it matches. */
    public record Row(Map<String, Condition> when, Earn earn) {

        /** True iff every field condition matches the payload (implicit AND). */
        public boolean matches(Map<String, Object> payload) {
            return when.entrySet().stream()
                    .allMatch(e -> e.getValue().test(payload.get(e.getKey())));
        }
    }

    /** RATE: {@code points} per {@code perAmount} of the event amount. FIXED: flat {@code points}. */
    public record Earn(EarnType type, Double perAmount, double points, Set<Balance> balances) {
    }

    /** Closed set of caps. {@code perEventMax} is stateless; the per-member caps live in cap_counter. */
    public record Caps(
            Integer perEventMax,
            Integer perMemberPerDay,
            Integer perMemberPerMonth,
            Integer perMemberPerRule
    ) {
        public static final Caps NONE = new Caps(null, null, null, null);
    }
}
