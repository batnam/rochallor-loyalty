package io.github.batnam.loyalty.earning.dsl;

import java.util.Map;

/**
 * The DSL Interpreter (C4 L3 §4 component 5) — a <b>pure function</b> with no I/O and no side effects,
 * which is what lets the hot path and the dry-run evaluator share it and what makes the rule semantics
 * unit-testable in isolation.
 *
 * <p>Evaluation order, per earning-rule.schema.json:
 * <ol>
 *   <li>walk rows; a row fires when every field condition matches (implicit AND);</li>
 *   <li>per matching row, compute points: RATE = {@code amount/perAmount × points}, FIXED = {@code points};
 *       then × tier multiplier (if {@code tierMultiplier}); then round ({@code FLOOR|ROUND|CEIL});</li>
 *   <li>route points to the row's selected balance(s);</li>
 *   <li>{@code hitPolicy}: FIRST stops at the first match, COLLECT sums every match;</li>
 *   <li>finally clamp each balance to {@code caps.perEventMax} (applied per balance — see note).</li>
 * </ol>
 *
 * <p><b>perEventMax note:</b> the schema calls it "max points this rule may award from a single
 * event". With the common {@code balances:[qualifying,redeemable]} this equals clamping the award;
 * when a rule splits balances across rows we clamp each balance independently — a deliberate v1
 * simplification documented in DETAILED-DESIGN.
 */
public class DslInterpreter {

    public EarnOutcome evaluate(RuleDsl dsl, Map<String, Object> payload, MemberContext ctx) {
        long qualifying = 0;
        long redeemable = 0;

        for (RuleDsl.Row row : dsl.rows()) {
            if (!row.matches(payload)) {
                continue;
            }
            long pts = rowPoints(dsl, row.earn(), payload, ctx);
            if (row.earn().balances().contains(RuleDsl.Balance.qualifying)) {
                qualifying += pts;
            }
            if (row.earn().balances().contains(RuleDsl.Balance.redeemable)) {
                redeemable += pts;
            }
            if (dsl.hitPolicy() == RuleDsl.HitPolicy.FIRST) {
                break;
            }
        }

        Integer perEventMax = dsl.caps().perEventMax();
        if (perEventMax != null) {
            qualifying = Math.min(qualifying, perEventMax);
            redeemable = Math.min(redeemable, perEventMax);
        }
        return new EarnOutcome(qualifying, redeemable);
    }

    private long rowPoints(RuleDsl dsl, RuleDsl.Earn earn, Map<String, Object> payload, MemberContext ctx) {
        double base = switch (earn.type()) {
            case RATE -> (amount(payload) / earn.perAmount()) * earn.points();
            case FIXED -> earn.points();
        };
        if (dsl.tierMultiplier()) {
            base *= ctx.multiplier();
        }
        return round(base, dsl.rounding());
    }

    private static double amount(Map<String, Object> payload) {
        Object a = payload.get("amount");
        return Condition.isNumber(a) ? Condition.toDouble(a) : 0d;
    }

    private static long round(double v, RuleDsl.Rounding mode) {
        return switch (mode) {
            case FLOOR -> (long) Math.floor(v);
            case CEIL -> (long) Math.ceil(v);
            case ROUND -> Math.round(v);
        };
    }
}
