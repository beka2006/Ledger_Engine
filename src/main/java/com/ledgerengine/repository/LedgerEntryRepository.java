package com.ledgerengine.repository;

import com.ledgerengine.model.LedgerEntry;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {

    /**
     * The transaction is fetched together with each entry. An entry's
     * transaction is a lazy reference, and with open-in-view turned off there
     * is no database session left by the time the REST layer builds its
     * response, so touching it later would throw LazyInitializationException.
     */
    @EntityGraph(attributePaths = {"transaction"})
    List<LedgerEntry> findByAccountId(UUID accountId);

    /**
     * Computes a balance directly in the database rather than loading every
     * entry into Java and summing them there — for an account with years of
     * history that difference is "one SQL aggregate" vs. "load thousands of
     * rows into memory." CASE WHEN turns DEBIT into +amount and CREDIT into
     * -amount, matching LedgerEntry#signedAmount's convention, then SUM
     * folds that into one number; COALESCE handles an account with zero
     * entries, where SUM would otherwise return SQL NULL instead of 0.
     */
    @Query("""
            SELECT COALESCE(SUM(
                CASE WHEN e.type = com.ledgerengine.model.EntryType.DEBIT THEN e.amount ELSE -e.amount END
            ), 0)
            FROM LedgerEntry e
            WHERE e.account.id = :accountId
            """)
    BigDecimal computeBalance(@Param("accountId") UUID accountId);
}
