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
import io.github.batnam.loyalty.earning.rule.SourceCapConfig;
import io.github.batnam.loyalty.earning.rule.SourceCaps;

import java.util.Optional;
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
 * <p>{@link MemberContext} carries the resolved member's tier-benefit earn multiplier (from core's
 * {@code /members/lookup}); it only bites on rules with {@code tierMultiplier:true} and is neutral
 * (1.0) otherwise. Rules default {@code tierMultiplier:false}.
 */
@Service
public class RuleEngine {

    private static final Logger log = LoggerFactory.getLogger(RuleEngine.class);

    private final IdempotencyRepository idempotency;
    private final Rules rules;
    private final SourceCaps sourceCaps;
    private final DslInterpreter interpreter;
    private final CapService caps;
    private final LedgerClient ledger;
    private final OutboxRelay outbox;
    private final EarningProperties props;

    public RuleEngine(IdempotencyRepository idempotency, Rules rules, SourceCaps sourceCaps,
                      DslInterpreter interpreter, CapService caps, LedgerClient ledger,
                      OutboxRelay outbox, EarningProperties props) {
        this.idempotency = idempotency;
        this.rules = rules;
        this.sourceCaps = sourceCaps;
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
        MemberContext ctx = new MemberContext(null, member.earnMultiplier().doubleValue());

        List<Long> entryIds = new ArrayList<>();
        long totalQualifying = 0;
        long totalRedeemable = 0;

        List<ActiveRule> active = rules.findActiveForSource(programId, event.source(), now);
        if (active.isEmpty()) {
            log.debug("no active rules for source '{}' on eventId={}", event.source(), event.eventId());
        }
        // OPTIONAL Source-Aggregate Cap (CONTEXT.md): one lookup per event, shared by every rule fire.
        Optional<SourceCapConfig> sourceCap = sourceCaps.findForSource(programId, event.source());
        for (ActiveRule rule : active) {
            EarnOutcome out = interpreter.evaluate(rule.dsl(), event.payload(), ctx);
            if (out.isZero()) {
                continue;
            }
            long capPoints = Math.max(out.qualifyingDelta(), out.redeemableDelta());
            if (!caps.tryConsume(programId, rule.ruleId(), memberId, rule.dsl().caps(), capPoints, now)) {
                log.debug("cap exhausted: rule={} member={} — dropping fire", rule.ruleId(), memberId);
                continue;
            }
            long qualifying = out.qualifyingDelta();
            long redeemable = out.redeemableDelta();
            if (sourceCap.isPresent()) {
                // "More restrictive applies": bound this fire by what the source cap can still absorb.
                long granted = caps.tryConsumeSourceCap(programId, memberId, sourceCap.get(), capPoints, now);
                if (granted < capPoints) {
                    if (granted <= 0) {
                        // Source cap exhausted: skip the fire and re-credit its rule-cap windows.
                        caps.credit(programId, rule.ruleId(), memberId, rule.dsl().caps(), capPoints, now);
                        log.debug("source cap exhausted: source={} member={} rule={} — dropping fire",
                                event.source(), memberId, rule.ruleId());
                        continue;
                    }
                    // Partial: scale each balance down to the granted total (floor).
                    qualifying = qualifying * granted / capPoints;
                    redeemable = redeemable * granted / capPoints;
                }
            }
            String sourceRef = event.eventId() + ":" + rule.ruleId();
            Long entryId = ledger.appendEarned(new LedgerEarnRequest(
                    memberId, programId, event.source(), sourceRef,
                    qualifying, redeemable,
                    currency(event), now), sourceRef);
            entryIds.add(entryId);
            totalQualifying += qualifying;
            totalRedeemable += redeemable;
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
