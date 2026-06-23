package com.trackam.controller;

import com.trackam.exception.TrackAmException;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(TrackAmException.class)
    public ProblemDetail handleTrackAmException(TrackAmException ex) {
        // Defaults to 400; total AI-provider outage carries 503 so clients can
        // tell "your input is wrong" from "try again in a minute".
        return ProblemDetail.forStatusAndDetail(ex.getStatus(), sanitize(ex.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleBadInput(IllegalArgumentException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, sanitize(ex.getMessage()));
    }

    @ExceptionHandler(SecurityException.class)
    public ProblemDetail handleSecurityViolation(SecurityException ex) {
        log.warn("Security violation detected: {}", ex.getMessage());
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, sanitize(ex.getMessage()));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
            .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
            .findFirst()
            .orElse("Invalid request");
        return ResponseEntity.badRequest().body(
            ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail));
    }

    // Bean Validation failures raised at the persistence layer (e.g. an entity @Size
    // exceeded). Without this they'd fall through to handleRuntime → 500; here they
    // surface as a clean 400. The constraint messages are safe, schema-free text.
    @ExceptionHandler(ConstraintViolationException.class)
    public ProblemDetail handleConstraintViolation(ConstraintViolationException ex) {
        String detail = ex.getConstraintViolations().stream()
            .map(v -> v.getPropertyPath() + ": " + v.getMessage())
            .findFirst()
            .orElse("Invalid request");
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail handleAccessDenied(AccessDeniedException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "Access denied");
    }

    // Handles ResponseStatusException from GoalService (404) and FxController (503)
    @ExceptionHandler(ResponseStatusException.class)
    public ProblemDetail handleResponseStatus(ResponseStatusException ex) {
        return ProblemDetail.forStatusAndDetail(
            HttpStatus.valueOf(ex.getStatusCode().value()),
            sanitize(ex.getReason() != null ? ex.getReason() : ex.getMessage()));
    }

    @ExceptionHandler(RuntimeException.class)
    public ProblemDetail handleRuntime(RuntimeException ex) {
        log.error("Unhandled runtime exception: {}", ex.getMessage(), ex);
        // Intentionally bypasses sanitize() — JPA constraint messages ("value too long for column")
        // and other short RuntimeException messages would pass the sanitizer and leak schema details.
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
            "An unexpected error occurred. Please try again.");
    }

    /** Strip internal details (URLs, exception class names, SQL) before sending to client. */
    private static String sanitize(String msg) {
        if (msg == null || msg.isBlank()) return "An error occurred. Please try again.";
        if (msg.contains("://") || msg.contains("Exception") || msg.contains("SQL")
                || msg.length() > 200) {
            return "An error occurred. Please try again.";
        }
        return msg;
    }
}
