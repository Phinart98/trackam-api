package com.trackam.exception;

public class TrackAmException extends RuntimeException {

    public TrackAmException(String message) {
        super(message);
    }

    public TrackAmException(String message, Throwable cause) {
        super(message, cause);
    }
}
