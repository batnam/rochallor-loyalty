package io.github.batnam.loyalty.earning.dsl;

import java.util.List;

/**
 * One cell of a decision-table row's predicate: a single comparison against one event-payload field
 * (earning-rule.schema.json {@code condition}). Sealed — the closed operator set is the whole point of
 * a constrained, non-Turing-complete DSL (L3 §7).
 *
 * <p>Matching rules: a non-{@link Wildcard} condition on an <b>absent</b> field never matches.
 * Comparisons coerce both sides to {@code double} when both are numeric; equality/membership compare
 * numerically when both numeric, else by string value.
 */
public sealed interface Condition {

    boolean test(Object fieldValue);

    /** {@code "*"} — matches any value, present or not. The catch-all / else branch. */
    record Wildcard() implements Condition {
        @Override public boolean test(Object v) { return true; }
    }

    record Eq(Object expected) implements Condition {
        @Override public boolean test(Object v) { return v != null && equalsCoerced(v, expected); }
    }

    record Ne(Object expected) implements Condition {
        @Override public boolean test(Object v) { return v == null || !equalsCoerced(v, expected); }
    }

    record In(List<Object> options) implements Condition {
        @Override public boolean test(Object v) {
            return v != null && options.stream().anyMatch(o -> equalsCoerced(v, o));
        }
    }

    record Nin(List<Object> options) implements Condition {
        @Override public boolean test(Object v) {
            return v == null || options.stream().noneMatch(o -> equalsCoerced(v, o));
        }
    }

    record Gt(double bound) implements Condition {
        @Override public boolean test(Object v) { return isNumber(v) && toDouble(v) > bound; }
    }

    record Gte(double bound) implements Condition {
        @Override public boolean test(Object v) { return isNumber(v) && toDouble(v) >= bound; }
    }

    record Lt(double bound) implements Condition {
        @Override public boolean test(Object v) { return isNumber(v) && toDouble(v) < bound; }
    }

    record Lte(double bound) implements Condition {
        @Override public boolean test(Object v) { return isNumber(v) && toDouble(v) <= bound; }
    }

    record Between(double low, double high) implements Condition {
        @Override public boolean test(Object v) {
            return isNumber(v) && toDouble(v) >= low && toDouble(v) <= high;
        }
    }

    // --- coercion helpers ----------------------------------------------------

    private static boolean equalsCoerced(Object a, Object b) {
        if (isNumber(a) && isNumber(b)) {
            return toDouble(a) == toDouble(b);
        }
        return String.valueOf(a).equals(String.valueOf(b));
    }

    static boolean isNumber(Object o) {
        if (o instanceof Number) return true;
        if (o instanceof String s) {
            try { Double.parseDouble(s); return true; } catch (NumberFormatException e) { return false; }
        }
        return false;
    }

    static double toDouble(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        return Double.parseDouble(String.valueOf(o));
    }
}
