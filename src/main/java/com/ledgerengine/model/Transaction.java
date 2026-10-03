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
 * debits and credits sum to zero before anything is saved - Transaction
 * itself is just the container, it doesn't validate its own entries.
 * <p>
 * An {@code idempotencyKey} field is included now, even though nothing uses
 * it yet (that's Phase 4) - adding it later would mean a schema migration
 * touching this exact table, so it's simpler to shape the table correctly
 * once. It's nullable for now and unused until then.
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

    public Instant getCreatedAt() {
        return createdAt;
    }

    public List<LedgerEntry> getEntries() {
        return entries;
    }
}
