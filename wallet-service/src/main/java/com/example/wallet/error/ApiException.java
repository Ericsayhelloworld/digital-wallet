package com.example.wallet.error;

import org.springframework.http.HttpStatus;

import java.util.List;

/** A business error with a status and a code from Section 6 of the specification. */
public class ApiException extends RuntimeException {

    public record FieldProblem(String field, String message) {
    }

    private final HttpStatus status;
    private final String code;
    private final String title;
    private final List<FieldProblem> errors;
    private Integer retryAfterSeconds;

    public ApiException(HttpStatus status, String code, String title, String detail) {
        this(status, code, title, detail, List.of());
    }

    public ApiException(HttpStatus status, String code, String title, String detail, List<FieldProblem> errors) {
        super(detail);
        this.status = status;
        this.code = code;
        this.title = title;
        this.errors = errors;
    }

    public static ApiException validation(String field, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Validation failed",
                "The request has invalid fields.", List.of(new FieldProblem(field, message)));
    }

    /** API-4: the same answer for a resource of another user and a resource that does not exist. */
    public static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Not found", "The resource does not exist.");
    }

    public static ApiException walletNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "WALLET_NOT_FOUND", "Wallet not found",
                "No wallet was found.");
    }

    public static ApiException unprocessable(String code, String title, String detail) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, title, detail);
    }

    public ApiException retryAfter(int seconds) {
        this.retryAfterSeconds = seconds;
        return this;
    }

    public HttpStatus getStatus() { return status; }
    public String getCode() { return code; }
    public String getTitle() { return title; }
    public List<FieldProblem> getErrors() { return errors; }
    public Integer getRetryAfterSeconds() { return retryAfterSeconds; }
}
