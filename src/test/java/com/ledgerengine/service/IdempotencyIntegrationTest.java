package com.ledgerengine.service;

import com.ledgerengine.exception.IdempotencyKeyReuseException;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end checks of idempotency against a real (in-memory) database. The
 * Mockito tests already cover the decision logic; what only a real database
 * can show is that the unique constraint, the rollback behaviour and the
 * lookup-after-losing-a-race recovery really work together.
 * <p>
 * Every test uses its own random idempotency key, because the key column is
 * unique across the whole database and the in-memory database is shared by
 * all test classes in the same run.
 */
@SpringBootTest
@ActiveProfiles("test")
class IdempotencyIntegrationTest {

    private static final BigDecimal INITIAL_BALANCE = new BigDecimal("100.00");
    private static final BigDecimal WITHDRAWAL = new BigDecimal("30.00");

    @Autowired
    private IdempotentPostingService idempotentPostingService;

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
        sourceId = accountRepository.save(new Account("Source", "EUR")).getId();
        destinationId = accountRepository.save(new Account("Destination", "EUR")).getId();
        fundingId = accountRepository.save(new Account("External funding", "EUR")).getId();

        // Same seeding approach as ConcurrentPostingIntegrationTest: money entering
        // the system from outside, written straight through the repository.
        Transaction seed = new Transaction("Initial funding");
        seed.addEntry(new LedgerEntry(accountRepository.getReferenceById(sourceId), EntryType.DEBIT, INITIAL_BALANCE));
        seed.addEntry(new LedgerEntry(accountRepository.getReferenceById(fundingId), EntryType.CREDIT, INITIAL_BALANCE));
        transactionRepository.save(seed);
    }

    private static String newKey() {
        return "test-key-" + UUID.randomUUID();
    }

    private List<EntryRequest> withdrawalEntries(BigDecimal amount) {
        return List.of(
                new EntryRequest(sourceId, EntryType.CREDIT, amount),
                new EntryRequest(destinationId, EntryType.DEBIT, amount)
        );
    }

    @Test
    void repeatedRequestWithTheSameKeyPostsOnlyOnce() {
        String key = newKey();

        PostingResult first = idempotentPostingService.post(key, "Withdrawal", withdrawalEntries(WITHDRAWAL));
        PostingResult second = idempotentPostingService.post(key, "Withdrawal", withdrawalEntries(WITHDRAWAL));

        assertThat(first.replayed()).isFalse();
        assertThat(second.replayed()).isTrue();
        assertThat(second.transaction().getId()).isEqualTo(first.transaction().getId());

        // The replayed transaction comes back from the database with its entries
        // already loaded (the @EntityGraph on the repository method).
        assertThat(second.transaction().getEntries()).hasSize(2);

        // Money moved once, not twice.
        assertThat(balanceService.getBalance(sourceId)).isEqualByComparingTo("70.00");
        assertThat(balanceService.getBalance(destinationId)).isEqualByComparingTo("30.00");
    }

    @Test
    void reusingAKeyForADifferentRequestIsRejectedAndPostsNothing() {
        String key = newKey();
        idempotentPostingService.post(key, "Withdrawal", withdrawalEntries(WITHDRAWAL));

        assertThatThrownBy(() ->
                idempotentPostingService.post(key, "Withdrawal", withdrawalEntries(new BigDecimal("40.00"))))
                .isInstanceOf(IdempotencyKeyReuseException.class);

        assertThat(balanceService.getBalance(sourceId)).isEqualByComparingTo("70.00");
    }

    @Test
    void differentKeysPostIndependently() {
        idempotentPostingService.post(newKey(), "Withdrawal", withdrawalEntries(WITHDRAWAL));
        idempotentPostingService.post(newKey(), "Withdrawal", withdrawalEntries(WITHDRAWAL));

        assertThat(balanceService.getBalance(sourceId)).isEqualByComparingTo("40.00");
    }

    /**
     * A posting that fails is rolled back completely, which includes the
     * idempotency key it carried. So the key stays free, and the client can
     * retry with the same key.
     */
    @Test
    void aFailedPostingDoesNotConsumeTheKey() {
        String key = newKey();

        assertThatThrownBy(() ->
                idempotentPostingService.post(key, "Too much", withdrawalEntries(new BigDecimal("500.00"))))
                .isInstanceOf(InsufficientFundsException.class);

        PostingResult retry = idempotentPostingService.post(key, "Withdrawal", withdrawalEntries(WITHDRAWAL));

        assertThat(retry.replayed()).isFalse();
        assertThat(balanceService.getBalance(sourceId)).isEqualByComparingTo("70.00");
    }

    /**
     * The hard case: several identical requests are released at the same
     * instant. All of them may find "no such key yet" and try to post; the
     * database's unique constraint lets exactly one win, and the rest must
     * recover by returning the winner's transaction.
     */
    @Test
    void identicalRequestsSentAtTheSameInstantPostExactlyOnce() throws InterruptedException {
        int threadCount = 5;
        String key = newKey();

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch allReady = new CountDownLatch(threadCount);
        CountDownLatch startSignal = new CountDownLatch(1);
        List<PostingResult> results = Collections.synchronizedList(new ArrayList<>());
        List<Throwable> unexpected = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threadCount; i++) {
            pool.submit(() -> {
                allReady.countDown();
                try {
                    startSignal.await();
                    results.add(idempotentPostingService.post(key, "Concurrent retry", withdrawalEntries(WITHDRAWAL)));
                } catch (Throwable t) {
                    unexpected.add(t);
                }
            });
        }

        allReady.await();
        startSignal.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).as("all threads finished").isTrue();

        assertThat(unexpected).as("unexpected exceptions").isEmpty();
        assertThat(results).hasSize(threadCount);

        // Exactly one request actually created the transaction...
        assertThat(results.stream().filter(result -> !result.replayed()).count()).isEqualTo(1);
        // ...and every caller got that same transaction back.
        assertThat(results.stream().map(result -> result.transaction().getId()).distinct().count()).isEqualTo(1);
        // Money moved exactly once.
        assertThat(balanceService.getBalance(sourceId)).isEqualByComparingTo("70.00");
    }
}
