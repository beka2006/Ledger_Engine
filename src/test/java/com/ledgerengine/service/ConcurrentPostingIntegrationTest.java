package com.ledgerengine.service;

import com.ledgerengine.exception.ConcurrentPostingException;
import com.ledgerengine.exception.InsufficientFundsException;
import com.ledgerengine.model.Account;
import com.ledgerengine.model.EntryType;
import com.ledgerengine.model.LedgerEntry;
import com.ledgerengine.model.Transaction;
import com.ledgerengine.repository.AccountRepository;
import com.ledgerengine.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unlike the Mockito tests, nothing here is mocked: real Spring context, real
 * JPA, a real (in-memory) database, and real threads. This is the first place
 * where the SQL in LedgerEntryRepository and the @Version check on Account
 * actually execute.
 * <p>
 * Note on the concurrency tests: whether the threads truly overlap depends on
 * timing, so the assertions are deliberately about INVARIANTS that must hold
 * no matter how the threads interleave (never overdrawn, ledger always
 * balanced), not about exactly which thread wins.
 */
@SpringBootTest
@ActiveProfiles("test")
class ConcurrentPostingIntegrationTest {

    private static final BigDecimal INITIAL_BALANCE = new BigDecimal("100.00");
    private static final BigDecimal WITHDRAWAL = new BigDecimal("30.00");

    @Autowired
    private PostingService postingService;

    @Autowired
    private BalanceService balanceService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    private UUID sourceId;
    private UUID destinationId;
    private UUID fundingId;

    @BeforeEach
    void setUp() {
        // Fresh accounts per test, so tests never see each other's entries.
        sourceId = accountRepository.save(new Account("Source", "EUR")).getId();
        destinationId = accountRepository.save(new Account("Destination", "EUR")).getId();
        fundingId = accountRepository.save(new Account("External funding", "EUR")).getId();

        seedInitialBalance();
    }

    /**
     * Gives the source account 100 EUR. This goes straight through the
     * repository instead of PostingService on purpose: PostingService would
     * (correctly) refuse to take 100 EUR out of a funding account that has
     * a balance of zero. The seed is still balanced (debit 100, credit 100),
     * it just represents money entering the system from outside it.
     */
    private void seedInitialBalance() {
        Transaction seed = new Transaction("Initial funding");
        seed.addEntry(new LedgerEntry(accountRepository.getReferenceById(sourceId), EntryType.DEBIT, INITIAL_BALANCE));
        seed.addEntry(new LedgerEntry(accountRepository.getReferenceById(fundingId), EntryType.CREDIT, INITIAL_BALANCE));
        transactionRepository.save(seed);
    }

    // ---------- Deterministic tests (no threads): prove the real SQL path works ----------

    @Test
    void withdrawalWithinBalanceSucceedsAndUpdatesBalances() {
        postingService.postTransaction("Withdrawal", withdrawalEntries(WITHDRAWAL));

        assertThat(balanceService.getBalance(sourceId)).isEqualByComparingTo("70.00");
        assertThat(balanceService.getBalance(destinationId)).isEqualByComparingTo("30.00");
    }

    @Test
    void withdrawalExceedingBalanceIsRejected() {
        assertThatThrownBy(() -> postingService.postTransaction("Too much", withdrawalEntries(new BigDecimal("150.00"))))
                .isInstanceOf(InsufficientFundsException.class);

        // Nothing was posted, so the balance must be untouched.
        assertThat(balanceService.getBalance(sourceId)).isEqualByComparingTo(INITIAL_BALANCE);
    }

    // ---------- Concurrency tests ----------

    @Test
    void concurrentWithdrawalsNeverOverdrawTheAccount() throws InterruptedException {
        int threadCount = 6; // 6 x 30 EUR = 180 EUR requested, but only 100 EUR available
        Outcome outcome = runConcurrentWithdrawals(threadCount);

        assertThat(outcome.unexpected()).as("unexpected exceptions").isEmpty();
        assertThat(outcome.successes() + outcome.concurrentFailures() + outcome.insufficientFailures())
                .isEqualTo(threadCount);

        // 100 / 30 -> at most 3 withdrawals can ever fit. At least one must win.
        assertThat(outcome.successes()).isBetween(1, 3);

        BigDecimal expected = INITIAL_BALANCE.subtract(WITHDRAWAL.multiply(BigDecimal.valueOf(outcome.successes())));
        BigDecimal actual = balanceService.getBalance(sourceId);

        assertThat(actual).isEqualByComparingTo(expected);
        assertThat(actual.signum()).as("balance must never go negative").isGreaterThanOrEqualTo(0);
    }

    @Test
    void ledgerStaysBalancedAfterConcurrentPostings() throws InterruptedException {
        Outcome outcome = runConcurrentWithdrawals(6);
        assertThat(outcome.unexpected()).as("unexpected exceptions").isEmpty();

        // Double-entry invariant: across ALL accounts, debits and credits cancel out.
        BigDecimal total = balanceService.getBalance(sourceId)
                .add(balanceService.getBalance(destinationId))
                .add(balanceService.getBalance(fundingId));

        assertThat(total).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ---------- Helpers ----------

    private List<EntryRequest> withdrawalEntries(BigDecimal amount) {
        return List.of(
                new EntryRequest(sourceId, EntryType.CREDIT, amount),
                new EntryRequest(destinationId, EntryType.DEBIT, amount)
        );
    }

    private record Outcome(int successes, int concurrentFailures, int insufficientFailures, List<Throwable> unexpected) {
    }

    /**
     * Starts N threads that all wait at a "starting gate" and are then released
     * at the same moment, each trying to withdraw WITHDRAWAL from the source
     * account. Without the gate, thread 1 could finish before thread 2 even
     * starts, and no race would ever happen.
     */
    private Outcome runConcurrentWithdrawals(int threadCount) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch allReady = new CountDownLatch(threadCount);
        CountDownLatch startSignal = new CountDownLatch(1);

        AtomicInteger successes = new AtomicInteger();
        AtomicInteger concurrentFailures = new AtomicInteger();
        AtomicInteger insufficientFailures = new AtomicInteger();
        List<Throwable> unexpected = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threadCount; i++) {
            pool.submit(() -> {
                allReady.countDown();
                try {
                    startSignal.await();
                    postingService.postTransaction("Concurrent withdrawal", withdrawalEntries(WITHDRAWAL));
                    successes.incrementAndGet();
                } catch (ConcurrentPostingException e) {
                    concurrentFailures.incrementAndGet();
                } catch (InsufficientFundsException e) {
                    insufficientFailures.incrementAndGet();
                } catch (Throwable t) {
                    unexpected.add(t);
                }
            });
        }

        allReady.await();
        startSignal.countDown();

        pool.shutdown();
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).as("all threads finished").isTrue();

        return new Outcome(successes.get(), concurrentFailures.get(), insufficientFailures.get(), unexpected);
    }
}
