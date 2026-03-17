package com.keer.ticketmaster.booking.service;

public class RemoteQueryException extends RuntimeException {
    public RemoteQueryException(String message) {
        super(message);
    }

    public RemoteQueryException(String message, Throwable cause) {
        super(message, cause);
    }
}
