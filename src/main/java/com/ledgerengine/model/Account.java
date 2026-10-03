package com.ledgerengine.model;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Represents a ledger account (e.g. a customer's wallet, or an internal
 * clearing account).
 * <p>
 * Deliberately has NO "balance" column. In a double-entry system, the
 * balance is never stored directly -- it's always derived by summing this
 * account's LedgerEntry rows (credits minus debits, or vice versa depending
 * on account type). Storing a balance field alongside the entries would let
 * the two drift out of sync (e.g. if a bug updates one but not the other);
 * computing it on demand makes that entire class of bug impossible.
 * <p>
 * A {@code long version} field is already here, unused for now - it's a
 * placeholder for the optimistic-locking commit later in the project, kept
 * visible from the start since it affects how this entity is shaped.
 */
@Entity
@Table(name = "accounts")
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String ownerName;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false)
    private Instant createdAt;

    @Version
    private long version;

    protected Account() {
        // required by JPA
    }

    public Account(String ownerName, String currency) {
        this.ownerName = ownerName;
        this.currency = currency;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getOwnerName() {
        return ownerName;
    }

    public String getCurrency() {
        return currency;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public long getVersion() {
        return version;
    }
}
