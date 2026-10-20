package com.ledgerengine.api;

import com.ledgerengine.exception.AccountNotFoundException;
import com.ledgerengine.exception.ConcurrentPostingException;
import com.ledgerengine.exception.IdempotencyKeyReuseException;
import com.ledgerengine.exception.InsufficientFundsException;
import com.ledgerengine.exception.TransactionNotFoundException;
import com.ledgerengine.exception.UnbalancedTransactionException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Translates the engine's domain exceptions into HTTP responses, in one place.
 * The service layer throws exceptions that describe what went wrong in
 * business terms; it knows nothing about HTTP. Choosing the status code is
 * this class's job, which is why none of the exception classes mention one.
 * <p>
 * Status code choices:
 * <ul>
 *   <li><b>400 Bad Request</b>: the request itself is malformed (bad JSON, a
 *       field failing validation, an id that isn't a UUID). Nothing about the
 *       ledger's state could have made it valid.</li>
 *   <li><b>404 Not Found</b>: the referenced account or transaction doesn't exist.</li>
 *   <li><b>422 Unprocessable Entity</b>: the request is well-formed but the
 *       ledger refuses it on business grounds (unbalanced, insufficient funds,
 *       idempotency key reused for a different request). Retrying the same
 *       request unchanged will not help.</li>
 *   <li><b>409 Conflict</b>: lost a race against another request touching the
 *       same account. Retrying the same request may well succeed, which is
 *       what separates it from 422.</li>
 * </ul>
 * Anything not listed here is deliberately left to Spring's default handling,
 * which answers 500 without leaking internals.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler({AccountNotFoundException.class, TransactionNotFoundException.class})
    public ResponseEntity<ApiError> handleNotFound(RuntimeException e) {
        return respond(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler({
            UnbalancedTransactionException.class,
            InsufficientFundsException.class,
            IdempotencyKeyReuseException.class
    })
    public ResponseEntity<ApiError> handleBusinessRuleViolation(RuntimeException e) {
        return respond(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
    }

    @ExceptionHandler(ConcurrentPostingException.class)
    public ResponseEntity<ApiError> handleConflict(ConcurrentPostingException e) {
        return respond(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException e) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors().forEach(error ->
                fieldErrors.merge(error.getField(), String.valueOf(error.getDefaultMessage()), (a, b) -> a + "; " + b));

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiError.of(HttpStatus.BAD_REQUEST, "Request validation failed", fieldErrors));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadableBody(HttpMessageNotReadableException e) {
        // The parser's own message can expose internal class names, so it is not echoed back.
        return respond(HttpStatus.BAD_REQUEST, "Malformed or unreadable request body");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return respond(HttpStatus.BAD_REQUEST, "Invalid value for parameter '" + e.getName() + "'");
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiError> handleMissingHeader(MissingRequestHeaderException e) {
        return respond(HttpStatus.BAD_REQUEST, "Missing required header: " + e.getHeaderName());
    }

    /**
     * Thrown by the domain for client mistakes the type system can't catch
     * (a non-positive amount, a blank idempotency key). Mapping every
     * IllegalArgumentException to 400 is a trade-off: a genuine bug that throws
     * one would also be reported as the client's fault. Acceptable here because
     * the domain code only throws it for bad input; a dedicated exception type
     * would remove the ambiguity if the codebase grows.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleIllegalArgument(IllegalArgumentException e) {
        return respond(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    private ResponseEntity<ApiError> respond(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(ApiError.of(status, message));
    }
}
