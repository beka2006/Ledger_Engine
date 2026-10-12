package com.ledgerengine.service;

import com.ledgerengine.exception.IdempotencyKeyReuseException;
import com.ledgerengine.model.Transaction;
import com.ledgerengine.repository.TransactionRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Makes posting safe to retry. Three situations can occur for a given key:
 * <ol>
 *   <li><b>Key never seen</b>: post the transaction normally and remember the key.</li>
 *   <li><b>Key seen, same request</b>: a retry. Return the original transaction
 *       and post nothing.</li>
 *   <li><b>Key seen, different request</b>: a client bug. Refuse with
 *       {@link IdempotencyKeyReuseException}.</li>
 * </ol>
 *
 * <h2>Why this class is NOT @Transactional</h2>
 * Two identical requests can arrive at the same instant. Both look the key up,
 * both find nothing, both try to post. The database's unique constraint on the
 * key lets exactly one INSERT succeed and makes the other fail with a
 * {@link DataIntegrityViolationException}. To recover, the loser must look up
 * the winner's transaction. But a database transaction that has hit a
 * constraint violation is poisoned (PostgreSQL refuses further statements in
 * it), so that lookup cannot happen inside the same transaction. Keeping this
 * class outside any transaction means PostingService runs in its own
 * transaction, which rolls back cleanly on the violation, and the recovery
 * lookup then runs in a fresh one.
 *
 * <h2>Retrying after a ConcurrentPostingException is safe</h2>
 * If a posting fails with ConcurrentPostingException, its transaction was
 * rolled back completely, so the key was never stored. The client can retry
 * with the same key and it will be processed as a brand-new request. That is
 * what makes the combination of optimistic locking and idempotency keys work
 * together.
 */
@Service
public class IdempotentPostingService {

    // After losing a race, the winner's transaction may not be visible yet (on
    // some databases the violation is reported before the winner has
    // committed), so the lookup is retried briefly before giving up.
    private static final int WINNER_LOOKUP_ATTEMPTS = 5;
    private static final long WINNER_LOOKUP_DELAY_MS = 50;

    private final PostingService postingService;
    private final TransactionRepository transactionRepository;

    public IdempotentPostingService(PostingService postingService, TransactionRepository transactionRepository) {
        this.postingService = postingService;
        this.transactionRepository = transactionRepository;
    }

    public PostingResult post(String idempotencyKey, String description, List<EntryRequest> entryRequests) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("An idempotency key is required");
        }

        String fingerprint = RequestFingerprint.of(description, entryRequests);

        Optional<Transaction> existing = transactionRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return replay(existing.get(), idempotencyKey, fingerprint);
        }

        try {
            Transaction created = postingService.postTransaction(description, entryRequests, idempotencyKey, fingerprint);
            return new PostingResult(created, false);
        } catch (DataIntegrityViolationException e) {
            // Either we lost a race against an identical request, or this is an
            // unrelated integrity problem. If no transaction with our key
            // exists, it was something else, so the original error is rethrown.
            Transaction winner = awaitWinner(idempotencyKey).orElseThrow(() -> e);
            return replay(winner, idempotencyKey, fingerprint);
        }
    }

    private PostingResult replay(Transaction stored, String idempotencyKey, String fingerprint) {
        if (!fingerprint.equals(stored.getRequestFingerprint())) {
            throw new IdempotencyKeyReuseException(idempotencyKey);
        }
        return new PostingResult(stored, true);
    }

    private Optional<Transaction> awaitWinner(String idempotencyKey) {
        for (int attempt = 1; attempt <= WINNER_LOOKUP_ATTEMPTS; attempt++) {
            Optional<Transaction> winner = transactionRepository.findByIdempotencyKey(idempotencyKey);
            if (winner.isPresent()) {
                return winner;
            }
            if (attempt < WINNER_LOOKUP_ATTEMPTS) {
                try {
                    Thread.sleep(WINNER_LOOKUP_DELAY_MS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return Optional.empty();
                }
            }
        }
        return Optional.empty();
    }
}
