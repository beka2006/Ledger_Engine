package com.ledgerengine.api;

import com.ledgerengine.service.EntryRequest;
import com.ledgerengine.service.IdempotentPostingService;
import com.ledgerengine.service.PostingResult;
import com.ledgerengine.service.TransactionQueryService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/transactions")
public class TransactionController {

    static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    static final String REPLAYED_HEADER = "Idempotent-Replayed";

    // The idempotency_key column is a VARCHAR(255). Checking here gives the
    // client a clear 400 instead of a database error deep inside the posting.
    private static final int MAX_KEY_LENGTH = 255;

    private final IdempotentPostingService idempotentPostingService;
    private final TransactionQueryService transactionQueryService;

    public TransactionController(IdempotentPostingService idempotentPostingService,
                                  TransactionQueryService transactionQueryService) {
        this.idempotentPostingService = idempotentPostingService;
        this.transactionQueryService = transactionQueryService;
    }

    /**
     * 201 Created for a new posting; 200 OK with an "Idempotent-Replayed: true"
     * header when the same Idempotency-Key was already used for this same
     * request and the original result is being returned instead of posting again.
     */
    @PostMapping
    public ResponseEntity<TransactionResponse> postTransaction(
            @RequestHeader(IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
            @Valid @RequestBody PostTransactionRequest request) {

        if (idempotencyKey.length() > MAX_KEY_LENGTH) {
            throw new IllegalArgumentException(IDEMPOTENCY_KEY_HEADER + " must be at most " + MAX_KEY_LENGTH + " characters");
        }

        List<EntryRequest> entries = request.entries().stream()
                .map(entry -> new EntryRequest(entry.accountId(), entry.type(), entry.amount()))
                .toList();

        PostingResult result = idempotentPostingService.post(idempotencyKey, request.description(), entries);
        TransactionResponse body = TransactionResponse.from(result.transaction());

        if (result.replayed()) {
            return ResponseEntity.ok().header(REPLAYED_HEADER, "true").body(body);
        }

        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(result.transaction().getId())
                .toUri();
        return ResponseEntity.created(location).body(body);
    }

    @GetMapping("/{id}")
    public TransactionResponse getTransaction(@PathVariable UUID id) {
        return TransactionResponse.from(transactionQueryService.getTransaction(id));
    }
}
