package com.ledgerengine.service;

import com.ledgerengine.exception.AccountNotFoundException;
import com.ledgerengine.exception.UnbalancedTransactionException;
import com.ledgerengine.model.Account;
import com.ledgerengine.model.EntryType;
import com.ledgerengine.model.LedgerEntry;
import com.ledgerengine.model.Transaction;
import com.ledgerengine.repository.AccountRepository;
import com.ledgerengine.repository.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * The one place in the system where money actually moves. Everything else
 * (REST controllers, later commits) is a thin wrapper around this.
 * <p>
 * Two guarantees this class exists to provide:
 * <ol>
 *   <li><b>Balance invariant</b> - a transaction is only posted if its debit
 *       entries and credit entries sum to the same amount. This is checked
 *       in Java, in memory, BEFORE any database write starts, so an
 *       unbalanced request never even begins a database transaction.</li>
 *   <li><b>Atomicity</b> - {@code @Transactional} means every entry in a
 *       posting either saves together, or (if anything after the balance
 *       check still fails - e.g. a constraint violation) none of them do.
 *       There's no code path that can leave a transaction with only some of
 *       its entries persisted.</li>
 * </ol>
 * What this class does NOT yet handle: concurrent postings to the same
 * account racing each other (Phase 3), or a caller retrying the same
 * request twice (Phase 4, idempotency). Both build on top of this method
 * without changing its core shape.
 */
@Service
public class PostingService {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;

    public PostingService(AccountRepository accountRepository, TransactionRepository transactionRepository) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
    }

    @Transactional
    public Transaction postTransaction(String description, List<EntryRequest> entryRequests) {
        validateBalanced(entryRequests);

        Transaction transaction = new Transaction(description);

        for (EntryRequest request : entryRequests) {
            Account account = accountRepository.findById(request.accountId())
                    .orElseThrow(() -> new AccountNotFoundException(request.accountId()));

            LedgerEntry entry = new LedgerEntry(account, request.type(), request.amount());
            transaction.addEntry(entry);
        }

        return transactionRepository.save(transaction);
    }

    /**
     * Sums debits and credits separately and compares them. Rejecting on
     * ANY mismatch (not just "credits exceed debits" or similar) is what
     * makes this a correctness check rather than a business rule - a
     * double-entry ledger that's even one cent off is corrupted, in either
     * direction.
     */
    private void validateBalanced(List<EntryRequest> entryRequests) {
        BigDecimal totalDebits = sumByType(entryRequests, EntryType.DEBIT);
        BigDecimal totalCredits = sumByType(entryRequests, EntryType.CREDIT);

        if (totalDebits.compareTo(totalCredits) != 0) {
            throw new UnbalancedTransactionException(totalDebits, totalCredits);
        }
    }

    private BigDecimal sumByType(List<EntryRequest> entryRequests, EntryType type) {
        return entryRequests.stream()
                .filter(request -> request.type() == type)
                .map(EntryRequest::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
