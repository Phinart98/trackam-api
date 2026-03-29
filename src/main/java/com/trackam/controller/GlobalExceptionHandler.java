package com.trackam.controller;

import com.trackam.exception.TrackAmException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(TrackAmException.class)
    public ProblemDetail handleTrackAmException(TrackAmException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, sanitize(ex.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleBadInput(IllegalArgumentException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, sanitize(ex.getMessage()));
    }

    @ExceptionHandler(SecurityException.class)
    public ProblemDetail handleSecurityViolation(SecurityException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, sanitize(ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
            .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
            .findFirst()
            .orElse("Invalid request");
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail handleAccessDenied(AccessDeniedException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "Access denied");
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
