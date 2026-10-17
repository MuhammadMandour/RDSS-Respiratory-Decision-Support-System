package com.rdss.common.error;

/** The request conflicts with the current state (duplicate booking, finalized record, ...). */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
