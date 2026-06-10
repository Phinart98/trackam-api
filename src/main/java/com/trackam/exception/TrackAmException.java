package com.trackam.exception;

import org.springframework.http.HttpStatus;

public class TrackAmException extends RuntimeException {

    private final HttpStatus status;

    public TrackAmException(String message) {
        this(message, HttpStatus.BAD_REQUEST);
    }

    public TrackAmException(String message, HttpStatus status) {
        super(message);
        this.status = status;
    }

    public TrackAmException(String message, Throwable cause) {
        this(message, cause, HttpStatus.BAD_REQUEST);
    }

    public TrackAmException(String message, Throwable cause, HttpStatus status) {
        super(message, cause);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
