package io.github.batnam.loyalty.campaign.campaign;

/**
 * Campaign lifecycle (loyalty-campaign.yaml).
 *
 * <pre>
 *   DRAFT ──▶ SCHEDULED ──▶ LIVE ──▶ ENDED ──▶ ARCHIVED
 *     │           │                              ▲
 *     ├───────────┴──────────────────────────────┘  (DRAFT/SCHEDULED may archive without going live)
 *     └──▶ LIVE                                       (DRAFT may go live directly)
 * </pre>
 *
 * Forward-only; {@code ARCHIVED} is terminal. The LIVE transition is approval-gated when the Campaign
 * carries earning multipliers — that economic gate lives in the service, not on this enum.
 */
public enum CampaignStatus {
    DRAFT,
    SCHEDULED,
    LIVE,
    ENDED,
    ARCHIVED;

    public boolean isTerminal() {
        return this == ARCHIVED;
    }

    /** @return true iff this -> {@code target} is a legal Campaign transition (never reflexive). */
    public boolean canTransitionTo(CampaignStatus target) {
        return switch (this) {
            case DRAFT -> target == SCHEDULED || target == LIVE || target == ARCHIVED;
            case SCHEDULED -> target == LIVE || target == ENDED || target == ARCHIVED;
            case LIVE -> target == ENDED;
            case ENDED -> target == ARCHIVED;
            case ARCHIVED -> false;
        };
    }
}
