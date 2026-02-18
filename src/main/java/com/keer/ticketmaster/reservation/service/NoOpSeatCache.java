package com.keer.ticketmaster.reservation.service;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("api")
public class NoOpSeatCache implements SeatAvailabilityChecker {

    @Override
    public boolean hasEnoughSeats(long eventId, String section, int seatCount) {
        return true;
    }
}
