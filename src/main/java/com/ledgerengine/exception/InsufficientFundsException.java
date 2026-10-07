package com.ledgerengine.exception;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Thrown when posting a transaction would take an account below zero.
 * <p>
 * This check happens inside the same @Transactional method that posts the
 * entries, using a balance read from the current transaction — see
 * PostingService for why that read alone isn't enough to fully prevent a
 * race between two concurrent withdrawals, and what {@code recordActivity()}
 * plus optimistic locking adds on top of it.
 */
public class InsufficientFundsException extends RuntimeException {

    public InsufficientFundsException(UUID accountId, BigDecimal available, BigDecimal requested) {
        super("Account " + accountId + " has insufficient funds: available = " + available
                + ", requested = " + requested);
    }
}
