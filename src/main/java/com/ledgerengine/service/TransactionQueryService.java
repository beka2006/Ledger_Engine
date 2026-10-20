package com.ledgerengine.service;

import com.ledgerengine.exception.TransactionNotFoundException;
import com.ledgerengine.model.Transaction;
import com.ledgerengine.repository.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Read-side lookup of a single posted transaction. */
@Service
public class TransactionQueryService {

    private final TransactionRepository transactionRepository;

    public TransactionQueryService(TransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    @Transactional(readOnly = true)
    public Transaction getTransaction(UUID transactionId) {
        return transactionRepository.findWithEntriesById(transactionId)
                .orElseThrow(() -> new TransactionNotFoundException(transactionId));
    }
}
