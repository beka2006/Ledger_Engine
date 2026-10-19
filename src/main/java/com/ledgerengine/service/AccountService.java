package com.ledgerengine.service;

import com.ledgerengine.exception.AccountNotFoundException;
import com.ledgerengine.model.Account;
import com.ledgerengine.repository.AccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Creating and looking up accounts. Kept apart from PostingService (money
 * movement) and BalanceService (balance reads): opening an account has
 * nothing to do with moving money, and none of those classes should need to
 * know how the others work.
 */
@Service
public class AccountService {

    private final AccountRepository accountRepository;

    public AccountService(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    @Transactional
    public Account createAccount(String ownerName, String currency, boolean overdraftAllowed) {
        return accountRepository.save(new Account(ownerName, currency, overdraftAllowed));
    }

    @Transactional(readOnly = true)
    public Account getAccount(UUID accountId) {
        return accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));
    }
}
