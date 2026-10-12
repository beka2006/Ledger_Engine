package com.ledgerengine.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * Turns a posting request into a short, fixed-length string such that the same
 * request always gives the same string, and any meaningful change (a different
 * amount, account, direction or description) gives a different one. Stored next
 * to the idempotency key, it lets a repeated request be recognised as "the same
 * thing again" instead of being compared field by field against stored entries.
 * <p>
 * Two details that matter for correctness:
 * <ul>
 *   <li>Amounts are normalised with {@code stripTrailingZeros()}, so 50.0 and
 *       50.00 (the same money, written differently) produce the same
 *       fingerprint. Without this, a client that formats numbers differently
 *       on a retry would be wrongly rejected.</li>
 *   <li>The description is length-prefixed, so a description that happens to
 *       contain the separator characters can't be made to look like extra
 *       entries and collide with a different request.</li>
 * </ul>
 * Entry order is part of the fingerprint: a retry is the same request sent
 * again, so it keeps the same order.
 */
public final class RequestFingerprint {

    private RequestFingerprint() {
    }

    public static String of(String description, List<EntryRequest> entryRequests) {
        String safeDescription = description == null ? "" : description;

        StringBuilder canonical = new StringBuilder();
        canonical.append(safeDescription.length()).append(':').append(safeDescription);

        for (EntryRequest entry : entryRequests) {
            canonical.append('|')
                    .append(entry.accountId()).append(':')
                    .append(entry.type()).append(':')
                    .append(entry.amount().stripTrailingZeros().toPlainString());
        }

        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] hash = sha256.digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
