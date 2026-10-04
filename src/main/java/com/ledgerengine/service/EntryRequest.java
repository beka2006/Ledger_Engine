package com.ledgerengine.service;

import com.ledgerengine.model.EntryType;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One line of a posting request: "debit/credit this account this amount."
 * A full transaction is a list of these - PostingService is what checks
 * that the list as a whole balances before touching the database.
 */
public record EntryRequest(UUID accountId, EntryType type, BigDecimal amount) {
}
