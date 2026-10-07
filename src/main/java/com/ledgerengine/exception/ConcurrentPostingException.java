package com.ledgerengine.exception;

/**
 * Thrown when two transactions tried to post against the same account at
 * the same time, and one of them lost the race (its account row's @Version
 * had already moved on by the time it tried to commit).
 * <p>
 * This wraps Spring's {@code ObjectOptimisticLockingFailureException} rather
 * than letting it escape directly, for the same reason PostingService's own
 * exceptions exist: callers of this service shouldn't need to know it's
 * built on JPA/Hibernate to handle "this posting needs to be retried."
 * Retrying is deliberately left to the caller (e.g. a REST controller could
 * retry once or twice) rather than handled silently in here — a method that
 * quietly retries itself can hide how often this is actually happening.
 */
public class ConcurrentPostingException extends RuntimeException {

    public ConcurrentPostingException(String message, Throwable cause) {
        super(message, cause);
    }
}
