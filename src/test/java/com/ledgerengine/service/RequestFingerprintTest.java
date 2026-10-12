package com.ledgerengine.service;

import com.ledgerengine.model.EntryType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RequestFingerprintTest {

    private final UUID accountA = UUID.randomUUID();
    private final UUID accountB = UUID.randomUUID();

    private List<EntryRequest> transfer(String amount) {
        return List.of(
                new EntryRequest(accountA, EntryType.CREDIT, new BigDecimal(amount)),
                new EntryRequest(accountB, EntryType.DEBIT, new BigDecimal(amount))
        );
    }

    @Test
    void identicalRequestsHaveIdenticalFingerprints() {
        assertThat(RequestFingerprint.of("Transfer", transfer("50.00")))
                .isEqualTo(RequestFingerprint.of("Transfer", transfer("50.00")));
    }

    @Test
    void fingerprintIsA64CharacterHexString() {
        assertThat(RequestFingerprint.of("Transfer", transfer("50.00"))).matches("[0-9a-f]{64}");
    }

    @Test
    void differentAmountChangesTheFingerprint() {
        assertThat(RequestFingerprint.of("Transfer", transfer("50.00")))
                .isNotEqualTo(RequestFingerprint.of("Transfer", transfer("60.00")));
    }

    @Test
    void differentDescriptionChangesTheFingerprint() {
        assertThat(RequestFingerprint.of("Rent", transfer("50.00")))
                .isNotEqualTo(RequestFingerprint.of("Gift", transfer("50.00")));
    }

    @Test
    void differentAccountChangesTheFingerprint() {
        List<EntryRequest> other = List.of(
                new EntryRequest(UUID.randomUUID(), EntryType.CREDIT, new BigDecimal("50.00")),
                new EntryRequest(accountB, EntryType.DEBIT, new BigDecimal("50.00"))
        );

        assertThat(RequestFingerprint.of("Transfer", transfer("50.00")))
                .isNotEqualTo(RequestFingerprint.of("Transfer", other));
    }

    @Test
    void swappingDebitAndCreditChangesTheFingerprint() {
        List<EntryRequest> reversed = List.of(
                new EntryRequest(accountA, EntryType.DEBIT, new BigDecimal("50.00")),
                new EntryRequest(accountB, EntryType.CREDIT, new BigDecimal("50.00"))
        );

        assertThat(RequestFingerprint.of("Transfer", transfer("50.00")))
                .isNotEqualTo(RequestFingerprint.of("Transfer", reversed));
    }

    @Test
    void numericallyEqualAmountsWithDifferentScaleGiveTheSameFingerprint() {
        assertThat(RequestFingerprint.of("Transfer", transfer("50.0")))
                .isEqualTo(RequestFingerprint.of("Transfer", transfer("50.00")));
    }

    @Test
    void descriptionCannotImpersonateAnEntry() {
        // Without length-prefixing the description, a description containing
        // the separators could be crafted to equal a different, real request.
        List<EntryRequest> none = List.of();
        String sneaky = "x|" + accountA + ":CREDIT:50";

        assertThat(RequestFingerprint.of(sneaky, none))
                .isNotEqualTo(RequestFingerprint.of("x", List.of(
                        new EntryRequest(accountA, EntryType.CREDIT, new BigDecimal("50")))));
    }
}
