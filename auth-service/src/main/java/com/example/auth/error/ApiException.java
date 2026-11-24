package com.example.auth.error;

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

    public HttpStatus getStatus() { return status; }
    public String getCode() { return code; }
    public String getTitle() { return title; }
    public List<FieldProblem> getErrors() { return errors; }
}
