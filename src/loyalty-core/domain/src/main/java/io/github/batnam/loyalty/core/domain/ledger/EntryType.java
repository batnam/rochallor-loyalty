package io.github.batnam.loyalty.core.domain.ledger;

/**
 * Point Ledger Entry types (CONTEXT.md "Point Ledger Entry"). Pure domain copy — the {@code :infra}
 * persistence model maps this to/from its own JPA-annotated enum. Standard delta effects
 * (qualifying, redeemable): Earned(+N,+N), Redeemed(0,−N), Expired(−N,−N), Reversed(−N,−N),
 * Adjusted(configurable).
 */
public enum EntryType {
    Earned, Redeemed, Expired, Reversed, Adjusted
}
