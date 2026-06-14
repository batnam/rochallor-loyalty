package io.github.batnam.loyalty.earning.replay;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.batnam.loyalty.earning.api.dto.EarningDtos.DryRunReport;
import io.github.batnam.loyalty.earning.dsl.DslInterpreter;
import io.github.batnam.loyalty.earning.dsl.EarnOutcome;
import io.github.batnam.loyalty.earning.dsl.MemberContext;
import io.github.batnam.loyalty.earning.error.EarningException;
import io.github.batnam.loyalty.earning.rule.DryRunTarget;
import io.github.batnam.loyalty.earning.rule.Rules;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The dry-run evaluator (L3 §3.3) — replays a window of historical EarnEvents from the replay store
 * through the <b>same</b> {@link DslInterpreter} the hot path uses, with <b>no side effects</b>: no
 * Cap Counter decrement, no Ledger write, no Idempotency Key write. What the operator sees is exactly
 * what the live engine would do on the next fire.
 */
@Service
@Transactional(readOnly = true)
public class DryRunService {

    private static final int SAMPLE_LIMIT = 10;

    private final Rules rules;
    private final EarnEventLogRepository replayStore;
    private final DslInterpreter interpreter;
    private final ObjectMapper mapper;

    public DryRunService(Rules rules, EarnEventLogRepository replayStore,
                         DslInterpreter interpreter, ObjectMapper mapper) {
        this.rules = rules;
        this.replayStore = replayStore;
        this.interpreter = interpreter;
        this.mapper = mapper;
    }

    public DryRunReport dryRun(long ruleId, Instant from, Instant to) {
        DryRunTarget target = rules.findForDryRun(ruleId)
                .orElseThrow(() -> EarningException.notFound("RULE_NOT_FOUND", "rule " + ruleId + " not found"));

        List<EarnEventLog> events = replayStore.findBySourceAndOccurredAtBetweenOrderByOccurredAtAsc(
                target.earnSourceCode(), from, to);

        int matched = 0;
        long totalQ = 0;
        long totalR = 0;
        List<DryRunReport.Sample> sample = new ArrayList<>();
        for (EarnEventLog e : events) {
            EarnOutcome out = interpreter.evaluate(target.dsl(), payload(e.getPayload()), MemberContext.none());
            if (out.isZero()) {
                continue;
            }
            matched++;
            totalQ += out.qualifyingDelta();
            totalR += out.redeemableDelta();
            if (sample.size() < SAMPLE_LIMIT) {
                sample.add(new DryRunReport.Sample(e.getEventId(), out.qualifyingDelta(), out.redeemableDelta()));
            }
        }
        return new DryRunReport(matched, events.size(), totalQ, totalR, sample);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> payload(String json) {
        try {
            return mapper.readValue(json, Map.class);
        } catch (Exception e) {
            throw new IllegalStateException("corrupt replay payload", e);
        }
    }
}
