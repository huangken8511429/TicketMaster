package com.keer.ticketmaster.reservation.service;

public interface SeatAvailabilityChecker {

    boolean hasEnoughSeats(long eventId, String section, int seatCount);

    default void set(long eventId, String section, int count) {}
}
