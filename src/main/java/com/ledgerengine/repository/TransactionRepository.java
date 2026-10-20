package com.ledgerengine.repository;

import com.ledgerengine.model.Transaction;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface TransactionRepository extends JpaRepository<Transaction, UUID> {

    /**
     * Looks up a previously posted transaction by its idempotency key, so a
     * retried request can be answered with the original result instead of
     * posting a duplicate.
     * <p>
     * {@code @EntityGraph} loads the entries, and the account each entry points
     * at, together with the transaction in one query. Both are lazy by default,
     * and a caller that gets this transaction back outside of a database
     * session (such as the REST layer building a response) would otherwise hit
     * a LazyInitializationException the first time it touched them.
     */
    @EntityGraph(attributePaths = {"entries", "entries.account"})
    Optional<Transaction> findByIdempotencyKey(String idempotencyKey);

    /** Same eager loading as above, for looking a transaction up by its id. */
    @EntityGraph(attributePaths = {"entries", "entries.account"})
    Optional<Transaction> findWithEntriesById(UUID id);
}
