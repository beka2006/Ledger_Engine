package com.ledgerengine.api;

import java.math.BigDecimal;
import java.util.UUID;

public record BalanceResponse(UUID accountId, String currency, BigDecimal balance) {
}
