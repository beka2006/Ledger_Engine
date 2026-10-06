package com.ledgerengine.service;

import com.ledgerengine.exception.AccountNotFoundException;
import com.ledgerengine.model.LedgerEntry;
import com.ledgerengine.repository.AccountRepository;
import com.ledgerengine.repository.LedgerEntryRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Read-side operations: balance and history. Kept separate from
 * PostingService on purpose - posting (writes) and balance lookups (reads)
 * have different concerns and, later, different performance characteristics
 * (e.g. a read replica could serve this service's queries in a larger
 * system). There's no reason for a class that computes a balance to also
 * know how to validate and persist a transaction.
 */
@Service
public class BalanceService {

    private final AccountRepository accountRepository;
    private final LedgerEntryRepository ledgerEntryRepository;

    public BalanceService(AccountRepository accountRepository, LedgerEntryRepository ledgerEntryRepository) {
        this.accountRepository = accountRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
    }

    public BigDecimal getBalance(UUID accountId) {
        requireAccountExists(accountId);
        return ledgerEntryRepository.computeBalance(accountId);
    }

    public List<LedgerEntry> getHistory(UUID accountId) {
        requireAccountExists(accountId);
        return ledgerEntryRepository.findByAccountId(accountId);
    }

    private void requireAccountExists(UUID accountId) {
        if (!accountRepository.existsById(accountId)) {
            throw new AccountNotFoundException(accountId);
        }
    }
}
