package io.github.batnam.loyalty.earning.caps;

import io.github.batnam.loyalty.earning.dsl.RuleDsl;
import io.github.batnam.loyalty.earning.rule.SourceCapConfig;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The Cap Counter (L3 §4 component 6). Enforces the per-member caps of a rule's DSL via the atomic
 * conditional decrement in {@link CapRepository}. A rule's award must fit in <b>all</b> of its
 * applicable windows (day / month / lifetime); if any is exhausted the whole fire is dropped and the
 * windows already decremented in this fire are re-credited (compensation within the caller's txn).
 *
 * <p>Runs {@code Propagation.MANDATORY}: it must enlist in the Rule Engine's transaction so the cap
 * decision commits-or-rolls-back together with the idempotency-key write (the Ledger REST call is
 * idempotent on core's side, so a rollback-then-retry never double-awards).
 *
 * <p>{@code perEventMax} is <b>not</b> handled here — it is stateless and applied by the
 * {@link io.github.batnam.loyalty.earning.dsl.DslInterpreter}.
 */
@Service
public class CapService {

    private final CapRepository caps;

    public CapService(CapRepository caps) {
        this.caps = caps;
    }

    /**
     * Try to consume {@code points} against every applicable cap window for this rule fire.
     * @return {@code true} if applied to all windows; {@code false} if any was exhausted (fire dropped).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean tryConsume(long programId, long ruleId, long memberId,
                              RuleDsl.Caps ruleCaps, long points, Instant at) {
        if (points <= 0) {
            return true;   // nothing to consume (no match / zero award)
        }
        List<String> decremented = new ArrayList<>();
        for (Window w : applicableWindows(ruleCaps)) {
            String key = w.window.keyFor(at);
            caps.ensureCounter(programId, ruleId, memberId, key, w.limit, w.window.expiresAt(at));
            int rows = caps.tryDecrement(programId, ruleId, memberId, key, points);
            if (rows == 0) {
                decremented.forEach(k -> caps.credit(programId, ruleId, memberId, k, points));
                return false;   // cap exhausted → drop the fire
            }
            decremented.add(key);
        }
        return true;
    }

    /**
     * Try to consume up to {@code points} against the per-member Source-Aggregate Cap (CONTEXT.md
     * "Source-Aggregate Cap") — the cumulative bound across ALL rules of a source per window. Unlike the
     * per-rule {@link #tryConsume} this is <b>partial</b>: it returns the points actually granted
     * ({@code 0..points}), so the Rule Engine awards only what the source cap can still absorb ("the more
     * restrictive applies"). The grant is the min the configured windows can supply together; any window
     * over-consumed relative to that min is re-credited (compensation within the caller's txn).
     *
     * <p>Keyed at the SOURCE level: it reuses {@code cap_counter} under the synthetic
     * {@link SourceCapConfig#counterRuleId()} (a negative id that cannot collide with real rule ids).
     *
     * @return the points granted by the source cap; {@code points} if no source cap is configured.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public long tryConsumeSourceCap(long programId, long memberId, SourceCapConfig cap,
                                    long points, Instant at) {
        if (points <= 0) {
            return points;
        }
        List<Window> windows = sourceWindows(cap);
        if (windows.isEmpty()) {
            return points;   // row exists but no window capped → uncapped
        }
        long ruleId = cap.counterRuleId();
        // First pass: partial-consume each window, remember how much each granted.
        long grant = points;
        List<Consumed> consumed = new ArrayList<>();
        for (Window w : windows) {
            String key = w.window.keyFor(at);
            caps.ensureCounter(programId, ruleId, memberId, key, w.limit, w.window.expiresAt(at));
            long got = caps.tryConsumePartial(programId, ruleId, memberId, key, points);
            consumed.add(new Consumed(key, got));
            grant = Math.min(grant, got);
        }
        // Second pass: re-credit any window that gave more than the binding (min) grant.
        for (Consumed c : consumed) {
            if (c.amount > grant) {
                caps.credit(programId, ruleId, memberId, c.key, c.amount - grant);
            }
        }
        return grant;
    }

    private List<Window> sourceWindows(SourceCapConfig cap) {
        List<Window> windows = new ArrayList<>();
        if (cap.dailyCap() != null)    windows.add(new Window(CapWindow.DAY, cap.dailyCap()));
        if (cap.monthlyCap() != null)  windows.add(new Window(CapWindow.MONTH, cap.monthlyCap()));
        if (cap.lifetimeCap() != null) windows.add(new Window(CapWindow.LIFE, cap.lifetimeCap()));
        return windows;
    }

    private record Consumed(String key, long amount) {
    }

    /**
     * Re-credit {@code points} to every applicable per-rule window — the compensation a caller invokes
     * when a rule passed its own cap but is then dropped by a LATER gate (the Source-Aggregate Cap), so
     * the rule-cap consume must be undone. Mirrors the in-fire compensation inside {@link #tryConsume}.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void credit(long programId, long ruleId, long memberId,
                       RuleDsl.Caps ruleCaps, long points, Instant at) {
        if (points <= 0) {
            return;
        }
        for (Window w : applicableWindows(ruleCaps)) {
            caps.credit(programId, ruleId, memberId, w.window.keyFor(at), points);
        }
    }

    private List<Window> applicableWindows(RuleDsl.Caps c) {
        List<Window> windows = new ArrayList<>();
        if (c.perMemberPerDay() != null)   windows.add(new Window(CapWindow.DAY, c.perMemberPerDay()));
        if (c.perMemberPerMonth() != null) windows.add(new Window(CapWindow.MONTH, c.perMemberPerMonth()));
        if (c.perMemberPerRule() != null)  windows.add(new Window(CapWindow.LIFE, c.perMemberPerRule()));
        return windows;
    }

    private record Window(CapWindow window, long limit) {
    }
}
