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
     * {@code @EntityGraph} loads the entries together with the transaction in
     * one query. Without it, the entries collection is lazy, and a caller
     * that gets this transaction back outside of a database session (such as
     * a REST controller building a response) would hit a
     * LazyInitializationException the first time it touched them.
     */
    @EntityGraph(attributePaths = {"entries"})
    Optional<Transaction> findByIdempotencyKey(String idempotencyKey);
}
