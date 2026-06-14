package io.github.batnam.loyalty.core.ledger;

/**
 * Point Ledger Entry types (CONTEXT.md "Point Ledger Entry"). Standard delta effects
 * (qualifying, redeemable): Earned(+N,+N), Redeemed(0,−N), Expired(−N,−N), Reversed(−N,−N),
 * Adjusted(configurable).
 */
public enum EntryType {
    Earned, Redeemed, Expired, Reversed, Adjusted
}
