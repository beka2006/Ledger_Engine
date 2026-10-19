package com.ledgerengine.api;

import com.ledgerengine.model.EntryType;
import com.ledgerengine.model.LedgerEntry;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record LedgerEntryResponse(
        UUID entryId,
        UUID transactionId,
        String description,
        EntryType type,
        BigDecimal amount,
        Instant postedAt
) {

    /** Expects the entry's transaction to be loaded (see LedgerEntryRepository#findByAccountId). */
    public static LedgerEntryResponse from(LedgerEntry entry) {
        return new LedgerEntryResponse(
                entry.getId(),
                entry.getTransaction().getId(),
                entry.getTransaction().getDescription(),
                entry.getType(),
                entry.getAmount(),
                entry.getTransaction().getCreatedAt());
    }
}
