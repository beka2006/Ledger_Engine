package com.ledgerengine.service;

import com.ledgerengine.exception.AccountNotFoundException;
import com.ledgerengine.exception.ConcurrentPostingException;
import com.ledgerengine.exception.InsufficientFundsException;
import com.ledgerengine.exception.UnbalancedTransactionException;
import com.ledgerengine.model.Account;
import com.ledgerengine.model.EntryType;
import com.ledgerengine.model.LedgerEntry;
import com.ledgerengine.model.Transaction;
import com.ledgerengine.repository.AccountRepository;
import com.ledgerengine.repository.TransactionRepository;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The one place in the system where money actually moves. Everything else
 * (REST controllers, later commits) is a thin wrapper around this.
 * <p>
 * Guarantees this class provides:
 * <ol>
 *   <li><b>Balance invariant</b> — a transaction is only posted if its debit
 *       entries and credit entries sum to the same amount, checked in Java
 *       before any database write starts.</li>
 *   <li><b>Atomicity</b> — {@code @Transactional} means every entry in a
 *       posting either saves together, or none do.</li>
 *   <li><b>No overdraft</b> — a CREDIT entry (money leaving an account) is
 *       rejected if it would take that account below zero, based on a
 *       balance read taken during this same method call.</li>
 *   <li><b>No lost updates under concurrency</b> — see the long comment
 *       below; this is the part that's easy to get subtly wrong.</li>
 * </ol>
 *
 * <h2>Why the balance check alone doesn't fully solve concurrency</h2>
 * Imagine an account with 100 EUR, and two requests arrive at nearly the same
 * instant, each trying to post a CREDIT (withdrawal) of 80 EUR:
 * <pre>
 *   Thread A: read balance -> 100       Thread B: read balance -> 100
 *   Thread A: 100 - 80 = 20, OK         Thread B: 100 - 80 = 20, OK
 *   Thread A: commits (-80)             Thread B: commits (-80)
 *   Final balance: -60   (should have been rejected!)
 * </pre>
 * Both threads read the balance BEFORE either one committed, so both saw
 * "100, plenty of room" and both proceeded. The overdraft check is correct
 * for a single request in isolation, but two requests running at once can
 * each pass it based on stale information.
 * <p>
 * <b>Optimistic locking closes this gap.</b> Every Account has a
 * {@code @Version} column (see Account#recordActivity). When Thread A posts,
 * it calls {@code account.recordActivity()} and flushes — this UPDATEs the
 * account row and bumps its version from, say, 5 to 6. When Thread B's
 * flush runs afterward, Hibernate's UPDATE includes "WHERE version = 5" (the
 * version Thread B originally read) — but the row is now at version 6, so
 * that UPDATE matches zero rows, and Hibernate throws
 * {@link ObjectOptimisticLockingFailureException}. Thread B's whole
 * transaction rolls back. The overdraft math was never the bug; letting two
 * transactions both act on the same "100 EUR" snapshot without either one
 * knowing about the other was. The version check is what makes that
 * impossible to miss.
 * <p>
 * Thread B's caller sees a {@link ConcurrentPostingException} and can retry
 * — on retry, it will read the real post-Thread-A balance (20 EUR) and
 * correctly get rejected by the overdraft check instead of silently
 * succeeding.
 */
@Service
public class PostingService {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final BalanceService balanceService;

    public PostingService(AccountRepository accountRepository,
                           TransactionRepository transactionRepository,
                           BalanceService balanceService) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.balanceService = balanceService;
    }

    @Transactional
    public Transaction postTransaction(String description, List<EntryRequest> entryRequests) {
        return postTransaction(description, entryRequests, null, null);
    }

    /**
     * Same as the two-argument version, but also stamps the transaction with
     * an idempotency key and a fingerprint of the request. This method does
     * NOT check whether the key was used before; that check lives in
     * IdempotentPostingService, which must run OUTSIDE any database
     * transaction (see the comment there). Here the key is simply stored, and
     * the unique constraint on that column is the last line of defence if two
     * requests with the same key slip through the check at the same time.
     */
    @Transactional
    public Transaction postTransaction(String description,
                                        List<EntryRequest> entryRequests,
                                        String idempotencyKey,
                                        String requestFingerprint) {
        validateBalanced(entryRequests);

        Transaction transaction = new Transaction(description);
        if (idempotencyKey != null) {
            transaction.setIdempotencyKey(idempotencyKey);
            transaction.setRequestFingerprint(requestFingerprint);
        }
        Map<UUID, Account> touchedAccounts = new LinkedHashMap<>();

        for (EntryRequest request : entryRequests) {
            Account account = touchedAccounts.computeIfAbsent(request.accountId(), this::loadAccount);

            if (request.type() == EntryType.CREDIT && !account.isOverdraftAllowed()) {
                checkSufficientFunds(request.accountId(), request.amount());
            }

            LedgerEntry entry = new LedgerEntry(account, request.type(), request.amount());
            transaction.addEntry(entry);
        }

        // Touch every account involved so its @Version is checked (and
        // bumped) on flush — see the class-level comment for why this step
        // is what actually makes optimistic locking take effect.
        touchedAccounts.values().forEach(Account::recordActivity);

        Transaction saved = transactionRepository.save(transaction);

        try {
            accountRepository.flush();
        } catch (ObjectOptimisticLockingFailureException e) {
            throw new ConcurrentPostingException(
                    "Another transaction modified one of these accounts first; please retry.", e);
        }

        return saved;
    }

    private Account loadAccount(UUID accountId) {
        return accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));
    }

    private void checkSufficientFunds(UUID accountId, BigDecimal requestedAmount) {
        BigDecimal available = balanceService.getBalance(accountId);
        if (available.subtract(requestedAmount).signum() < 0) {
            throw new InsufficientFundsException(accountId, available, requestedAmount);
        }
    }

    /**
     * Sums debits and credits separately and compares them. Rejecting on
     * ANY mismatch (not just "credits exceed debits" or similar) is what
     * makes this a correctness check rather than a business rule — a
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
