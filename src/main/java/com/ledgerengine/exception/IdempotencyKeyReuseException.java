package com.ledgerengine.exception;

/**
 * Thrown when a request arrives with an idempotency key that was already used
 * for a DIFFERENT request. Returning the old transaction here would be wrong
 * (the caller asked for something else and would believe it happened), and
 * posting a new one would defeat the purpose of the key, so the only safe
 * answer is to refuse. In practice it means a bug in the client, such as
 * generating one key and reusing it for unrelated payments.
 */
public class IdempotencyKeyReuseException extends RuntimeException {

    public IdempotencyKeyReuseException(String idempotencyKey) {
        super("Idempotency key '" + idempotencyKey + "' was already used with a different request");
    }
}
