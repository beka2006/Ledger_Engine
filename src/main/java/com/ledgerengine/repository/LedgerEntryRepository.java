package com.ledgerengine.repository;

import com.ledgerengine.model.LedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {

    List<LedgerEntry> findByAccountId(UUID accountId);

    /**
     * Computes a balance directly in the database rather than loading every
     * entry into Java and summing them there - for an account with years of
     * history that difference is "one SQL aggregate" vs. "load thousands of
     * rows into memory." CASE WHEN turns DEBIT into +amount and CREDIT into
     * -amount, matching LedgerEntry#signedAmount's convention, then SUM
     * folds that into one number; COALESCE handles an account with zero
     * entries, where SUM would otherwise return SQL NULL instead of 0.
     */
    @Query("SELECT COALESCE(SUM(\n" +
           "    CASE WHEN e.type = com.ledgerengine.model.EntryType.DEBIT THEN e.amount ELSE -e.amount END\n" +
           "), 0)\n" +
           "FROM LedgerEntry e\n" +
           "WHERE e.account.id = :accountId\n")
    BigDecimal computeBalance(@Param("accountId") UUID accountId);
}
