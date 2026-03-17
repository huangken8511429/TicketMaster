package com.keer.ticketmaster.exception;

public class StoreNotReadyException extends RuntimeException {
    public StoreNotReadyException(String message) {
        super(message);
    }
}
