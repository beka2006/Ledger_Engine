package com.ledgerengine.api;

import com.ledgerengine.model.EntryType;
import com.ledgerengine.model.Transaction;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Expects the transaction's entries, and the account of each entry, to be
 * loaded already: freshly posted transactions have them in memory, and
 * transactions read from the database are fetched with an entity graph.
 */
public record TransactionResponse(UUID id, String description, Instant postedAt, List<EntryResponse> entries) {

    public record EntryResponse(UUID accountId, EntryType type, BigDecimal amount) {
    }

    public static TransactionResponse from(Transaction transaction) {
        List<EntryResponse> entries = transaction.getEntries().stream()
                .map(entry -> new EntryResponse(entry.getAccount().getId(), entry.getType(), entry.getAmount()))
                .toList();
        return new TransactionResponse(transaction.getId(), transaction.getDescription(), transaction.getCreatedAt(), entries);
    }
}
