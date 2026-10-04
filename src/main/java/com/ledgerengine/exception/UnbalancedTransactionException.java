package com.ledgerengine.exception;

import java.math.BigDecimal;

/**
 * Thrown when a transaction's debit entries and credit entries don't sum to
 * the same amount. This is the single most important guard in the whole
 * engine - everything else about double-entry correctness depends on this
 * check happening before anything is persisted.
 */
public class UnbalancedTransactionException extends RuntimeException {

    public UnbalancedTransactionException(BigDecimal totalDebits, BigDecimal totalCredits) {
        super("Transaction is not balanced: total debits = " + totalDebits
                + ", total credits = " + totalCredits);
    }
}
