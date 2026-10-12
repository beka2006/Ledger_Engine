package com.ledgerengine.service;

import com.ledgerengine.exception.IdempotencyKeyReuseException;
import com.ledgerengine.model.EntryType;
import com.ledgerengine.model.Transaction;
import com.ledgerengine.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IdempotentPostingServiceTest {

    private static final String KEY = "key-123";
    private static final String DESCRIPTION = "Transfer";

    @Mock
    private PostingService postingService;

    @Mock
    private TransactionRepository transactionRepository;

    private IdempotentPostingService service;
    private List<EntryRequest> entries;
    private String fingerprint;

    @BeforeEach
    void setUp() {
        service = new IdempotentPostingService(postingService, transactionRepository);
        entries = List.of(
                new EntryRequest(UUID.randomUUID(), EntryType.CREDIT, new BigDecimal("50.00")),
                new EntryRequest(UUID.randomUUID(), EntryType.DEBIT, new BigDecimal("50.00"))
        );
        fingerprint = RequestFingerprint.of(DESCRIPTION, entries);
    }

    private Transaction storedTransaction(String storedFingerprint) {
        Transaction stored = new Transaction(DESCRIPTION);
        stored.setIdempotencyKey(KEY);
        stored.setRequestFingerprint(storedFingerprint);
        return stored;
    }

    @Test
    void anUnseenKeyPostsANewTransaction() {
        Transaction created = new Transaction(DESCRIPTION);
        when(transactionRepository.findByIdempotencyKey(KEY)).thenReturn(Optional.empty());
        when(postingService.postTransaction(DESCRIPTION, entries, KEY, fingerprint)).thenReturn(created);

        PostingResult result = service.post(KEY, DESCRIPTION, entries);

        assertThat(result.replayed()).isFalse();
        assertThat(result.transaction()).isSameAs(created);
    }

    @Test
    void aRepeatedRequestReturnsTheOriginalWithoutPostingAgain() {
        Transaction stored = storedTransaction(fingerprint);
        when(transactionRepository.findByIdempotencyKey(KEY)).thenReturn(Optional.of(stored));

        PostingResult result = service.post(KEY, DESCRIPTION, entries);

        assertThat(result.replayed()).isTrue();
        assertThat(result.transaction()).isSameAs(stored);
        verifyNoInteractions(postingService);
    }

    @Test
    void reusingAKeyForADifferentRequestIsRejected() {
        when(transactionRepository.findByIdempotencyKey(KEY))
                .thenReturn(Optional.of(storedTransaction("fingerprint-of-some-other-request")));

        assertThatThrownBy(() -> service.post(KEY, DESCRIPTION, entries))
                .isInstanceOf(IdempotencyKeyReuseException.class);

        verifyNoInteractions(postingService);
    }

    @Test
    void losingARaceAgainstAnIdenticalRequestReturnsTheWinnersTransaction() {
        Transaction winner = storedTransaction(fingerprint);
        // First lookup (before posting) finds nothing; the lookup after the
        // constraint violation finds the winner.
        when(transactionRepository.findByIdempotencyKey(KEY))
                .thenReturn(Optional.empty(), Optional.of(winner));
        when(postingService.postTransaction(DESCRIPTION, entries, KEY, fingerprint))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));

        PostingResult result = service.post(KEY, DESCRIPTION, entries);

        assertThat(result.replayed()).isTrue();
        assertThat(result.transaction()).isSameAs(winner);
    }

    @Test
    void losingARaceAgainstADifferentRequestWithTheSameKeyIsRejected() {
        Transaction winner = storedTransaction("fingerprint-of-some-other-request");
        when(transactionRepository.findByIdempotencyKey(KEY))
                .thenReturn(Optional.empty(), Optional.of(winner));
        when(postingService.postTransaction(DESCRIPTION, entries, KEY, fingerprint))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));

        assertThatThrownBy(() -> service.post(KEY, DESCRIPTION, entries))
                .isInstanceOf(IdempotencyKeyReuseException.class);
    }

    @Test
    void anUnrelatedIntegrityViolationIsRethrown() {
        DataIntegrityViolationException unrelated = new DataIntegrityViolationException("something else broke");
        when(transactionRepository.findByIdempotencyKey(KEY)).thenReturn(Optional.empty());
        when(postingService.postTransaction(DESCRIPTION, entries, KEY, fingerprint)).thenThrow(unrelated);

        assertThatThrownBy(() -> service.post(KEY, DESCRIPTION, entries))
                .isSameAs(unrelated);
    }

    @Test
    void aBlankKeyIsRejectedBeforeAnythingElseHappens() {
        assertThatThrownBy(() -> service.post("  ", DESCRIPTION, entries))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.post(null, DESCRIPTION, entries))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(postingService);
        verifyNoInteractions(transactionRepository);
    }
}
