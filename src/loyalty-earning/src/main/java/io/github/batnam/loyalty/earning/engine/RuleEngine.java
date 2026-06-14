package io.github.batnam.loyalty.earning.engine;

import io.github.batnam.loyalty.earning.caps.CapService;
import io.github.batnam.loyalty.earning.config.EarningProperties;
import io.github.batnam.loyalty.earning.consume.EarnEvent;
import io.github.batnam.loyalty.earning.dsl.DslInterpreter;
import io.github.batnam.loyalty.earning.dsl.EarnOutcome;
import io.github.batnam.loyalty.earning.dsl.MemberContext;
import io.github.batnam.loyalty.earning.event.PointsEarnedEvent;
import io.github.batnam.loyalty.earning.ledger.LedgerClient;
import io.github.batnam.loyalty.earning.ledger.LedgerEarnRequest;
import io.github.batnam.loyalty.earning.member.MemberRef;
import io.github.batnam.loyalty.earning.outbox.OutboxRelay;
import io.github.batnam.loyalty.earning.rule.ActiveRule;
import io.github.batnam.loyalty.earning.rule.Rules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The Rule Engine (L3 §4 component 4) — the hot path. For a resolved {@code (memberId, programId)} it:
 * <ol>
 *   <li><b>idempotency gate</b> — short-circuit replays before any rule work;</li>
 *   <li><b>rule resolution</b> — every ACTIVE rule for the (program, source) whose window contains now;</li>
 *   <li>per rule: DSL eval → cap check → Ledger write (one entry per rule, sourceRef = eventId:ruleId);</li>
 *   <li>enqueue one {@code PointsEarned} event summarising the fire (CONTEXT.md "Conflict" = sum);</li>
 *   <li>record the idempotency key — all in one transaction.</li>
 * </ol>
 *
 * <p>The Ledger REST call is the one non-transactional step; it is idempotent on core's side
 * ({@code (sourceRef, entryType)}), so a rollback-then-redeliver never double-awards.
 *
 * <p><b>v1 simplification:</b> {@link MemberContext} is neutral (multiplier 1.0) — the tier-benefit
 * multiplier for {@code tierMultiplier} rules is owned by the (not-yet-built) Program config; wiring
 * it through the member lookup is deferred. Rules default {@code tierMultiplier:false}.
 */
@Service
public class RuleEngine {

    private static final Logger log = LoggerFactory.getLogger(RuleEngine.class);

    private final IdempotencyRepository idempotency;
    private final Rules rules;
    private final DslInterpreter interpreter;
    private final CapService caps;
    private final LedgerClient ledger;
    private final OutboxRelay outbox;
    private final EarningProperties props;

    public RuleEngine(IdempotencyRepository idempotency, Rules rules, DslInterpreter interpreter,
                      CapService caps, LedgerClient ledger, OutboxRelay outbox, EarningProperties props) {
        this.idempotency = idempotency;
        this.rules = rules;
        this.interpreter = interpreter;
        this.caps = caps;
        this.ledger = ledger;
        this.outbox = outbox;
        this.props = props;
    }

    @Transactional
    public EarnResult process(EarnEvent event, MemberRef member) {
        if (idempotency.existsById(event.eventId())) {
            log.debug("replay of eventId={} — short-circuit", event.eventId());
            return EarnResult.replay();
        }

        Instant now = event.occurredAt();
        long programId = member.programId();
        long memberId = member.memberId();

        List<Long> entryIds = new ArrayList<>();
        long totalQualifying = 0;
        long totalRedeemable = 0;

        List<ActiveRule> active = rules.findActiveForSource(programId, event.source(), now);
        if (active.isEmpty()) {
            log.debug("no active rules for source '{}' on eventId={}", event.source(), event.eventId());
        }
        for (ActiveRule rule : active) {
            EarnOutcome out = interpreter.evaluate(rule.dsl(), event.payload(), MemberContext.none());
            if (out.isZero()) {
                continue;
            }
            long capPoints = Math.max(out.qualifyingDelta(), out.redeemableDelta());
            if (!caps.tryConsume(programId, rule.ruleId(), memberId, rule.dsl().caps(), capPoints, now)) {
                log.debug("cap exhausted: rule={} member={} — dropping fire", rule.ruleId(), memberId);
                continue;
            }
            String sourceRef = event.eventId() + ":" + rule.ruleId();
            Long entryId = ledger.appendEarned(new LedgerEarnRequest(
                    memberId, programId, event.source(), sourceRef,
                    out.qualifyingDelta(), out.redeemableDelta(),
                    currency(event), now), sourceRef);
            entryIds.add(entryId);
            totalQualifying += out.qualifyingDelta();
            totalRedeemable += out.redeemableDelta();
        }

        if (!entryIds.isEmpty()) {
            PointsEarnedEvent evt = PointsEarnedEvent.of(event.eventId(), programId, memberId,
                    totalQualifying, totalRedeemable, entryIds, now);
            outbox.enqueue("earning", "PointsEarned", props.topics().pointsEarned(),
                    String.valueOf(memberId), evt);
        }

        idempotency.save(IdempotencyKey.of(event.eventId(), memberId, programId));
        return new EarnResult(false, entryIds, totalQualifying, totalRedeemable);
    }

    private static String currency(EarnEvent event) {
        Object c = event.payload().get("currency");
        return c == null ? null : String.valueOf(c);
    }
}
