package com.ledgerengine.repository;

import com.ledgerengine.model.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface TransactionRepository extends JpaRepository<Transaction, UUID> {

    /**
     * Looked up before posting a new transaction, so a retried request with
     * the same idempotency key can return the original result instead of
     * posting a duplicate. The actual retry logic is added in the Phase 4
     * commits - this method is just the data-access piece it will need.
     */
    Optional<Transaction> findByIdempotencyKey(String idempotencyKey);
}
