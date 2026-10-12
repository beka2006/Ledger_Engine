package com.ledgerengine.model;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A Transaction is the unit of atomicity: it groups two or more LedgerEntry
 * rows that must be posted together, or not at all. The posting service
 * (added in a later commit) is what actually enforces that the entries'
 * debits and credits sum to zero before anything is saved — Transaction
 * itself is just the container, it doesn't validate its own entries.
 * <p>
 * {@code idempotencyKey} is the client-chosen token that makes retries safe:
 * the database enforces that no two transactions share one (unique column),
 * which is what ultimately stops a duplicate even if two identical requests
 * arrive at the same instant. {@code requestFingerprint} is a hash of what
 * the request actually asked for, stored so that a later request reusing the
 * same key can be checked for "same request again" (a legitimate retry) versus
 * "different request, same key" (a client bug that must not be silently
 * answered with the wrong transaction). Both are null for postings made
 * without a key.
 */
@Entity
@Table(name = "transactions")
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String description;

    @Column(unique = true)
    private String idempotencyKey;

    @Column(length = 64)
    private String requestFingerprint;

    @Column(nullable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "transaction", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<LedgerEntry> entries = new ArrayList<>();

    protected Transaction() {
        // required by JPA
    }

    public Transaction(String description) {
        this.description = description;
        this.createdAt = Instant.now();
    }

    public void addEntry(LedgerEntry entry) {
        entries.add(entry);
        entry.setTransaction(this);
    }

    public UUID getId() {
        return id;
    }

    public String getDescription() {
        return description;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public String getRequestFingerprint() {
        return requestFingerprint;
    }

    public void setRequestFingerprint(String requestFingerprint) {
        this.requestFingerprint = requestFingerprint;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public List<LedgerEntry> getEntries() {
        return entries;
    }
}
