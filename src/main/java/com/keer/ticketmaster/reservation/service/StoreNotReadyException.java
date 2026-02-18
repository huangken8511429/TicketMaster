package com.keer.ticketmaster.reservation.service;

public class StoreNotReadyException extends RuntimeException {
    public StoreNotReadyException(String message) {
        super(message);
    }
}
