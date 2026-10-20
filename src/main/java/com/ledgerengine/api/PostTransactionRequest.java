package com.ledgerengine.api;

import com.ledgerengine.model.EntryType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Body of POST /api/transactions. This only checks that the request is
 * well-formed. Whether the entries actually balance, whether the accounts exist
 * and whether they have enough money are business rules, decided by
 * PostingService, not here.
 * <p>
 * At least two entries are required because a single entry can never balance:
 * every amount is positive, so one debit has no credit to cancel it.
 */
public record PostTransactionRequest(
        @NotBlank(message = "description is required")
        @Size(max = 255, message = "description must be at most 255 characters")
        String description,

        @NotNull(message = "entries are required")
        @Size(min = 2, max = 100, message = "a transaction needs between 2 and 100 entries")
        @Valid
        List<@NotNull(message = "an entry must not be null") @Valid EntryPayload> entries
) {

    public record EntryPayload(
            @NotNull(message = "accountId is required")
            UUID accountId,

            @NotNull(message = "type is required (DEBIT or CREDIT)")
            EntryType type,

            // 15 integer digits and 4 decimals is exactly what the NUMERIC(19,4)
            // column can hold; anything more precise would be silently rounded.
            @NotNull(message = "amount is required")
            @Positive(message = "amount must be greater than 0")
            @Digits(integer = 15, fraction = 4, message = "amount allows at most 15 integer digits and 4 decimals")
            BigDecimal amount
    ) {
    }
}
