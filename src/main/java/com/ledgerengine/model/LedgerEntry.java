package com.ledgerengine.model;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * The atomic fact of the ledger: "this account was debited/credited this
 * amount, as part of this transaction." Everything else in the system
 * (account balances, statements, audit trails) is a view computed from rows
 * in this table - nothing else is a source of truth.
 * <p>
 * Entries are intentionally immutable after creation (no setters besides
 * what JPA needs): a mistake in accounting is never "corrected" by editing
 * or deleting a row, only by posting a new, opposite entry that cancels it
 * out. That's not enforced by Java code here - it's enforced by simply never
 * writing an update/delete method for this entity anywhere in the project.
 * {@code amount} is always positive; EntryType (DEBIT/CREDIT) carries the
 * direction, so there's no ambiguity like "does -50 mean a debit or a
 * negative credit?"
 */
@Entity
@Table(name = "ledger_entries")
public class LedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "transaction_id", nullable = false)
    private Transaction transaction;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private EntryType type;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    protected LedgerEntry() {
        // required by JPA
    }

    public LedgerEntry(Account account, EntryType type, BigDecimal amount) {
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("Ledger entry amount must be positive; got " + amount);
        }
        this.account = account;
        this.type = type;
        this.amount = amount;
    }

    void setTransaction(Transaction transaction) {
        this.transaction = transaction;
    }

    public UUID getId() {
        return id;
    }

    public Account getAccount() {
        return account;
    }

    public Transaction getTransaction() {
        return transaction;
    }

    public EntryType getType() {
        return type;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    /**
     * Signed amount from the account's point of view, used when summing a
     * balance: positive for a type that increases the account, negative for
     * the type that decreases it. Which EntryType increases which account
     * normally depends on account type (asset vs liability) - this simple
     * convention (DEBIT = +, CREDIT = -) is intentionally fixed for now and
     * revisited once account "kinds" are introduced.
     */
    public BigDecimal signedAmount() {
        return type == EntryType.DEBIT ? amount : amount.negate();
    }
}
