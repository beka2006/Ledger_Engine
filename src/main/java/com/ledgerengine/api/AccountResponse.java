package com.ledgerengine.api;

import com.ledgerengine.model.Account;

import java.time.Instant;
import java.util.UUID;

/**
 * What the API returns for an account. The JPA entity is deliberately never
 * sent to clients directly: the entity's shape is an internal storage detail
 * (it carries the @Version column, lazy relations, and so on), and exposing
 * it would make every internal refactor a breaking change for API users.
 */
public record AccountResponse(UUID id, String ownerName, String currency, boolean overdraftAllowed, Instant createdAt) {

    public static AccountResponse from(Account account) {
        return new AccountResponse(
                account.getId(),
                account.getOwnerName(),
                account.getCurrency(),
                account.isOverdraftAllowed(),
                account.getCreatedAt());
    }
}
