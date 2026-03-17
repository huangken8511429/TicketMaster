package com.keer.ticketmaster.booking.service;

public class StoreNotReadyException extends RuntimeException {
    public StoreNotReadyException(String message) {
        super(message);
    }
}
