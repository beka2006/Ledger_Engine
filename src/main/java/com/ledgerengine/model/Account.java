package com.ledgerengine.model;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Represents a ledger account (e.g. a customer's wallet, or an internal
 * clearing account).
 * <p>
 * Deliberately has NO "balance" column. In a double-entry system, the
 * balance is never stored directly - it's always derived by summing this
 * account's LedgerEntry rows (credits minus debits, or vice versa depending
 * on account type). Storing a balance field alongside the entries would let
 * the two drift out of sync (e.g. if a bug updates one but not the other);
 * computing it on demand makes that entire class of bug impossible.
 * <p>
 * The {@code version} field is what makes optimistic locking work: every
 * UPDATE to this row increments it, and Hibernate adds "AND version = ?" to
 * that UPDATE's WHERE clause behind the scenes. If two transactions read the
 * same Account (same version) and both try to commit a change, the second
 * one's UPDATE matches zero rows (the version has already moved on) and
 * Hibernate throws instead of silently overwriting the first transaction's
 * work. See {@link #recordActivity()} for why posting a transaction needs to
 * actually change something on Account to make this trigger at all.
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
    private boolean overdraftAllowed;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant lastActivityAt;

    @Version
    private long version;

    protected Account() {
        // required by JPA
    }

    public Account(String ownerName, String currency) {
        this(ownerName, currency, false);
    }

    /**
     * @param overdraftAllowed true for accounts that are SUPPOSED to be able to
     *        go negative, such as an "external funding" account that represents
     *        the outside world. Customer accounts leave this false, so
     *        PostingService refuses any posting that would overdraw them.
     *        Without such an account there would be no way to get money into
     *        the system at all: every account would need a balance before it
     *        could pay anything out.
     */
    public Account(String ownerName, String currency, boolean overdraftAllowed) {
        this.ownerName = ownerName;
        this.currency = currency;
        this.overdraftAllowed = overdraftAllowed;
        this.createdAt = Instant.now();
    }

    /**
     * Called once per account touched by a posting. On its own this is a
     * reasonable thing to track (an audit trail of "when did this account
     * last see activity"), but it also has a second job: LedgerEntry rows
     * reference an Account without ever modifying it, so without some real
     * field changing here, posting two entries to the same Account would
     * never actually produce an UPDATE on the accounts table - and with no
     * UPDATE, {@code version} never gets checked, and optimistic locking has
     * nothing to catch. This method is what turns "post a transaction" into
     * "also update the account row," which is what the @Version check needs.
     */
    public void recordActivity() {
        this.lastActivityAt = Instant.now();
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

    public boolean isOverdraftAllowed() {
        return overdraftAllowed;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastActivityAt() {
        return lastActivityAt;
    }

    public long getVersion() {
        return version;
    }
}
