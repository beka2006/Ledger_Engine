package com.ledgerengine.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body of POST /api/accounts. These constraints are checked by Spring before
 * the controller method runs; a violation never reaches the service layer and
 * is turned into a 400 response by ApiExceptionHandler.
 * <p>
 * {@code overdraftAllowed} is a Boolean (not boolean) on purpose, so that
 * "field omitted" can be told apart from "false" and treated as false.
 */
public record CreateAccountRequest(
        @NotBlank(message = "ownerName is required")
        @Size(max = 255, message = "ownerName must be at most 255 characters")
        String ownerName,

        @NotBlank(message = "currency is required")
        @Pattern(regexp = "[A-Z]{3}", message = "currency must be a 3-letter uppercase ISO code, e.g. EUR")
        String currency,

        Boolean overdraftAllowed
) {
}
