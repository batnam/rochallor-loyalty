package io.github.batnam.loyalty.earning.api.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.batnam.loyalty.earning.source.EarnSource;
import io.github.batnam.loyalty.earning.source.EarningRule;

import java.time.Instant;
import java.util.List;

/**
 * Request/response records for the loyalty-earning internal API (loyalty-earning.yaml).
 *
 * <p>{@code dslJson} is typed as {@code Object} (a plain Map/List tree), not a Jackson tree node:
 * Spring Boot 4's web layer is Jackson 3 ({@code tools.jackson}) while the platform pins Jackson 2
 * ({@code com.fasterxml}, used by networknt + the outbox). Carrying it as a plain {@code Object}
 * sidesteps the version split; the service converts to a Jackson-2 node internally for validation.
 */
public final class EarningDtos {

    private EarningDtos() {
    }

    public record EarnSourceResponse(Long earnSourceId, String earnSourceCode, String displayName,
                                     boolean activeByDefault) {
        public static EarnSourceResponse from(EarnSource s) {
            return new EarnSourceResponse(s.getEarnSourceId(), s.getEarnSourceCode(),
                    s.getDisplayName(), s.isActiveByDefault());
        }
    }

    public record EarningRuleResponse(Long ruleId, Long programId, Long earnSourceId, Object dslJson,
                                      int version, String status, Instant effectiveFrom, Instant effectiveTo,
                                      Long campaignId, Instant createdAt, Instant updatedAt) {
        public static EarningRuleResponse from(EarningRule r, ObjectMapper mapper) {
            Object dsl;
            try {
                dsl = mapper.readValue(r.getDslJson(), Object.class);   // plain Map/List tree
            } catch (Exception e) {
                throw new IllegalStateException("corrupt dsl_json for rule " + r.getRuleId(), e);
            }
            return new EarningRuleResponse(r.getRuleId(), r.getProgramId(), r.getEarnSourceId(), dsl,
                    r.getVersion(), r.getStatus().name(), r.getEffectiveFrom(), r.getEffectiveTo(),
                    r.getCampaignId(), r.getCreatedAt(), r.getUpdatedAt());
        }
    }

    public record RuleCreateRequest(Long earnSourceId, Object dslJson, Instant effectiveFrom,
                                    Instant effectiveTo, Long campaignId) {
    }

    public record RuleStatusRequest(String status, String bepApprovalRef) {
    }

    /** Dry-run window — explicit [from, to] range over the replay store (side-effect-free). */
    public record DryRunRequest(Instant from, Instant to) {
    }

    public record DryRunReport(int matchedEvents, int totalEvents, long totalQualifying,
                               long totalRedeemable, List<Sample> sample) {
        public record Sample(String eventId, long qualifyingDelta, long redeemableDelta) {
        }
    }
}
