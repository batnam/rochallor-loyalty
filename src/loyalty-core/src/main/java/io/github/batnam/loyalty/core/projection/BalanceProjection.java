package io.github.batnam.loyalty.core.projection;

import io.github.batnam.loyalty.core.member.Member;
import org.springframework.stereotype.Component;

/**
 * Single writer of the Member balance columns ({@code redeemable_balance}, {@code qualifying_balance}).
 * Called by the Ledger Service on every successful insert, inside the same transaction (CONTEXT.md
 * invariant "Single writer to Member.points_balance"). Balances may go negative — that is explicitly
 * allowed (CONTEXT.md "Negative Redeemable Balance").
 */
@Component
public class BalanceProjection {

    public void applyDelta(Member member, long redeemableDelta, long qualifyingDelta) {
        member.applyBalanceDelta(redeemableDelta, qualifyingDelta);
    }
}
