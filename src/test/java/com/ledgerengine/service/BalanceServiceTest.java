package com.ledgerengine.service;

import com.ledgerengine.exception.AccountNotFoundException;
import com.ledgerengine.model.Account;
import com.ledgerengine.model.EntryType;
import com.ledgerengine.model.LedgerEntry;
import com.ledgerengine.repository.AccountRepository;
import com.ledgerengine.repository.LedgerEntryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BalanceServiceTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private LedgerEntryRepository ledgerEntryRepository;

    private BalanceService balanceService;

    @Test
    void returnsTheComputedBalanceForAnExistingAccount() {
        balanceService = new BalanceService(accountRepository, ledgerEntryRepository);
        UUID accountId = UUID.randomUUID();

        when(accountRepository.existsById(accountId)).thenReturn(true);
        when(ledgerEntryRepository.computeBalance(accountId)).thenReturn(new BigDecimal("125.50"));

        BigDecimal balance = balanceService.getBalance(accountId);

        assertThat(balance).isEqualByComparingTo("125.50");
    }

    @Test
    void throwsWhenGettingBalanceForAnUnknownAccount() {
        balanceService = new BalanceService(accountRepository, ledgerEntryRepository);
        UUID accountId = UUID.randomUUID();

        when(accountRepository.existsById(accountId)).thenReturn(false);

        assertThatThrownBy(() -> balanceService.getBalance(accountId))
                .isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    void returnsHistoryForAnExistingAccount() {
        balanceService = new BalanceService(accountRepository, ledgerEntryRepository);
        UUID accountId = UUID.randomUUID();
        Account account = new Account("Alice", "EUR");
        List<LedgerEntry> history = List.of(new LedgerEntry(account, EntryType.DEBIT, new BigDecimal("20.00")));

        when(accountRepository.existsById(accountId)).thenReturn(true);
        when(ledgerEntryRepository.findByAccountId(accountId)).thenReturn(history);

        List<LedgerEntry> result = balanceService.getHistory(accountId);

        assertThat(result).hasSize(1);
    }

    @Test
    void throwsWhenGettingHistoryForAnUnknownAccount() {
        balanceService = new BalanceService(accountRepository, ledgerEntryRepository);
        UUID accountId = UUID.randomUUID();

        when(accountRepository.existsById(accountId)).thenReturn(false);

        assertThatThrownBy(() -> balanceService.getHistory(accountId))
                .isInstanceOf(AccountNotFoundException.class);
    }
}
