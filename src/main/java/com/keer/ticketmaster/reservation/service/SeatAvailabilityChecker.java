package com.keer.ticketmaster.reservation.service;

public interface SeatAvailabilityChecker {

    boolean hasEnoughSeats(long eventId, String section, int seatCount);
}
