package com.ledgerengine.service;

import com.ledgerengine.exception.AccountNotFoundException;
import com.ledgerengine.exception.InsufficientFundsException;
import com.ledgerengine.exception.UnbalancedTransactionException;
import com.ledgerengine.model.Account;
import com.ledgerengine.model.EntryType;
import com.ledgerengine.model.Transaction;
import com.ledgerengine.repository.AccountRepository;
import com.ledgerengine.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PostingServiceTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private BalanceService balanceService;

    private PostingService postingService;

    private UUID sourceAccountId;
    private UUID destinationAccountId;
    private Account sourceAccount;
    private Account destinationAccount;

    @BeforeEach
    void setUp() {
        postingService = new PostingService(accountRepository, transactionRepository, balanceService);

        sourceAccountId = UUID.randomUUID();
        destinationAccountId = UUID.randomUUID();
        sourceAccount = new Account("Alice", "EUR");
        destinationAccount = new Account("Bob", "EUR");
    }

    @Test
    void postsATransactionWhenDebitsAndCreditsAreBalancedAndFundsAreSufficient() {
        when(accountRepository.findById(sourceAccountId)).thenReturn(Optional.of(sourceAccount));
        when(accountRepository.findById(destinationAccountId)).thenReturn(Optional.of(destinationAccount));
        when(balanceService.getBalance(sourceAccountId)).thenReturn(new BigDecimal("200.00"));
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(invocation -> invocation.getArgument(0));

        List<EntryRequest> entries = List.of(
                new EntryRequest(sourceAccountId, EntryType.CREDIT, new BigDecimal("50.00")),
                new EntryRequest(destinationAccountId, EntryType.DEBIT, new BigDecimal("50.00"))
        );

        Transaction result = postingService.postTransaction("Transfer", entries);

        assertThat(result.getEntries()).hasSize(2);
        assertThat(result.getDescription()).isEqualTo("Transfer");
        verify(transactionRepository, times(1)).save(any(Transaction.class));
        verify(accountRepository, times(1)).flush();
    }

    @Test
    void rejectsATransactionWhenDebitsAndCreditsDoNotMatch() {
        List<EntryRequest> entries = List.of(
                new EntryRequest(sourceAccountId, EntryType.CREDIT, new BigDecimal("50.00")),
                new EntryRequest(destinationAccountId, EntryType.DEBIT, new BigDecimal("40.00"))
        );

        assertThatThrownBy(() -> postingService.postTransaction("Bad transfer", entries))
                .isInstanceOf(UnbalancedTransactionException.class)
                .hasMessageContaining("50.00")
                .hasMessageContaining("40.00");

        // The balance check must happen before any database or balance-service
        // interaction — proves the method fails fast, not after a partial write.
        verifyNoInteractions(accountRepository);
        verifyNoInteractions(transactionRepository);
        verifyNoInteractions(balanceService);
    }

    @Test
    void rejectsATransactionReferencingAnUnknownAccount() {
        when(accountRepository.findById(sourceAccountId)).thenReturn(Optional.empty());

        List<EntryRequest> entries = List.of(
                new EntryRequest(sourceAccountId, EntryType.CREDIT, new BigDecimal("50.00")),
                new EntryRequest(destinationAccountId, EntryType.DEBIT, new BigDecimal("50.00"))
        );

        assertThatThrownBy(() -> postingService.postTransaction("Transfer", entries))
                .isInstanceOf(AccountNotFoundException.class);

        verify(transactionRepository, never()).save(any());
    }

    @Test
    void rejectsAWithdrawalThatWouldOverdrawTheAccount() {
        when(accountRepository.findById(sourceAccountId)).thenReturn(Optional.of(sourceAccount));
        when(balanceService.getBalance(sourceAccountId)).thenReturn(new BigDecimal("30.00"));

        List<EntryRequest> entries = List.of(
                new EntryRequest(sourceAccountId, EntryType.CREDIT, new BigDecimal("50.00")),
                new EntryRequest(destinationAccountId, EntryType.DEBIT, new BigDecimal("50.00"))
        );

        assertThatThrownBy(() -> postingService.postTransaction("Overdraft attempt", entries))
                .isInstanceOf(InsufficientFundsException.class);

        verify(transactionRepository, never()).save(any());
    }

    @Test
    void acceptsAThreeWaySplitAsLongAsTotalsMatchAndFundsAreSufficient() {
        UUID thirdAccountId = UUID.randomUUID();
        Account thirdAccount = new Account("Carol", "EUR");

        when(accountRepository.findById(sourceAccountId)).thenReturn(Optional.of(sourceAccount));
        when(accountRepository.findById(destinationAccountId)).thenReturn(Optional.of(destinationAccount));
        when(accountRepository.findById(thirdAccountId)).thenReturn(Optional.of(thirdAccount));
        when(balanceService.getBalance(sourceAccountId)).thenReturn(new BigDecimal("500.00"));
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(invocation -> invocation.getArgument(0));

        List<EntryRequest> entries = List.of(
                new EntryRequest(sourceAccountId, EntryType.CREDIT, new BigDecimal("100.00")),
                new EntryRequest(destinationAccountId, EntryType.DEBIT, new BigDecimal("60.00")),
                new EntryRequest(thirdAccountId, EntryType.DEBIT, new BigDecimal("40.00"))
        );

        Transaction result = postingService.postTransaction("Split bill", entries);

        assertThat(result.getEntries()).hasSize(3);
    }

    @Test
    void anAccountThatAllowsOverdraftCanPayOutWithoutABalanceCheck() {
        Account fundingAccount = new Account("External funding", "EUR", true);

        when(accountRepository.findById(sourceAccountId)).thenReturn(Optional.of(fundingAccount));
        when(accountRepository.findById(destinationAccountId)).thenReturn(Optional.of(destinationAccount));
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(invocation -> invocation.getArgument(0));

        List<EntryRequest> entries = List.of(
                new EntryRequest(sourceAccountId, EntryType.CREDIT, new BigDecimal("500.00")),
                new EntryRequest(destinationAccountId, EntryType.DEBIT, new BigDecimal("500.00"))
        );

        Transaction result = postingService.postTransaction("Funding", entries);

        assertThat(result.getEntries()).hasSize(2);
        // The balance of an overdraft-allowed account is never even looked up.
        verifyNoInteractions(balanceService);
    }
}
