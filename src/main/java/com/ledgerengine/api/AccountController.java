package com.ledgerengine.api;

import com.ledgerengine.model.Account;
import com.ledgerengine.model.LedgerEntry;
import com.ledgerengine.service.AccountService;
import com.ledgerengine.service.BalanceService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Thin HTTP layer: parse the request, call a service, shape the response.
 * No business rules live here; if a rule needs to change, it changes in the
 * service layer and every caller (this controller included) gets it.
 */
@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    private final AccountService accountService;
    private final BalanceService balanceService;

    public AccountController(AccountService accountService, BalanceService balanceService) {
        this.accountService = accountService;
        this.balanceService = balanceService;
    }

    @PostMapping
    public ResponseEntity<AccountResponse> createAccount(@Valid @RequestBody CreateAccountRequest request) {
        Account account = accountService.createAccount(
                request.ownerName(),
                request.currency(),
                Boolean.TRUE.equals(request.overdraftAllowed()));

        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(account.getId())
                .toUri();

        return ResponseEntity.created(location).body(AccountResponse.from(account));
    }

    @GetMapping("/{id}")
    public AccountResponse getAccount(@PathVariable UUID id) {
        return AccountResponse.from(accountService.getAccount(id));
    }

    @GetMapping("/{id}/balance")
    public BalanceResponse getBalance(@PathVariable UUID id) {
        Account account = accountService.getAccount(id);
        return new BalanceResponse(account.getId(), account.getCurrency(), balanceService.getBalance(id));
    }

    /** Newest first. */
    @GetMapping("/{id}/entries")
    public List<LedgerEntryResponse> getEntries(@PathVariable UUID id) {
        return balanceService.getHistory(id).stream()
                .sorted(Comparator.comparing((LedgerEntry entry) -> entry.getTransaction().getCreatedAt()).reversed())
                .map(LedgerEntryResponse::from)
                .toList();
    }
}
