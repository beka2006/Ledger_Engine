package com.ledgerengine.model;

/**
 * Every ledger entry is either a DEBIT or a CREDIT. Neither is inherently
 * "money in" or "money out" - that depends on the account type (an asset
 * account increases with a debit; a liability account increases with a
 * credit). What matters structurally is simpler and stricter: in any single
 * Transaction, the DEBIT entries' amounts must sum to exactly the CREDIT
 * entries' amounts. That invariant is what makes double-entry bookkeeping
 * self-balancing - it's enforced in the posting logic added in a later
 * commit, not here; this enum just names the two sides.
 */
public enum EntryType {
    DEBIT,
    CREDIT
}
