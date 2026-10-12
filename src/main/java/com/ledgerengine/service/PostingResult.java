package com.ledgerengine.service;

import com.ledgerengine.model.Transaction;

/**
 * Outcome of an idempotent posting. {@code replayed} is true when the
 * transaction was found from an earlier request with the same key rather than
 * created now. The REST layer can use it to answer 201 Created for a new
 * posting and 200 OK for a replay.
 */
public record PostingResult(Transaction transaction, boolean replayed) {
}
