package io.github.batnam.loyalty.earning.dsl;

/**
 * The member-side inputs the DSL Interpreter needs: the current tier code and its benefits multiplier
 * (applied when a rule sets {@code tierMultiplier: true}). PII-free. {@link #none()} is the neutral
 * context (multiplier 1.0) used when a rule does not depend on tier.
 */
public record MemberContext(String tierCode, double multiplier) {

    public static MemberContext none() {
        return new MemberContext(null, 1.0);
    }
}
