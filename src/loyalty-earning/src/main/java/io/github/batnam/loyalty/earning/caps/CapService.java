package io.github.batnam.loyalty.earning.caps;

import io.github.batnam.loyalty.earning.dsl.RuleDsl;
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
