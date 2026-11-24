package com.example.auth.error;

import com.example.auth.error.ApiException.FieldProblem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ProblemDetail> handleApi(ApiException e) {
        ProblemDetail problem = Problems.build(e.getStatus(), e.getCode(), e.getTitle(), e.getMessage());
        if (!e.getErrors().isEmpty()) {
            problem.setProperty("errors", e.getErrors());
        }
        return ResponseEntity.status(e.getStatus()).body(problem);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleInvalid(MethodArgumentNotValidException e) {
        List<FieldProblem> errors = e.getBindingResult().getFieldErrors().stream()
                .map(f -> new FieldProblem(f.getField(), f.getDefaultMessage()))
                .toList();
        ProblemDetail problem = Problems.build(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                "Validation failed", "The request has invalid fields.");
        problem.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> handleUnreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest().body(Problems.build(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                "Validation failed", "The request body is missing or is not valid JSON."));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleOther(Exception e) {
        // Spring's own errors such as 404 (no such path) or 405 (wrong method)
        if (e instanceof ErrorResponse springError) {
            HttpStatusCode status = springError.getStatusCode();
            String code = switch (status.value()) {
                case 404 -> "NOT_FOUND";
                case 405 -> "METHOD_NOT_ALLOWED";
                default -> status.is4xxClientError() ? "VALIDATION_FAILED" : "INTERNAL_ERROR";
            };
            return ResponseEntity.status(status)
                    .body(Problems.build(status, code, "Request failed", "The request could not be handled."));
        }
        // API-6: no stack trace and no SQL message in the response; the log keeps the details
        log.error("Unexpected error", e);
        return ResponseEntity.internalServerError().body(Problems.build(HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_ERROR", "Internal error", "An unexpected error occurred."));
    }
}
