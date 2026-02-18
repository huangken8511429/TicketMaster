package com.keer.ticketmaster.reservation.service;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
@Profile({"seat-processor", "default"})
public class AvailableSeatCache implements SeatAvailabilityChecker {

    private final ConcurrentHashMap<String, AtomicInteger> available = new ConcurrentHashMap<>();

    @Override
    public void set(long eventId, String section, int count) {
        available.computeIfAbsent(eventId + "-" + section, k -> new AtomicInteger()).set(count);
    }

    @Override
    public boolean hasEnoughSeats(long eventId, String section, int seatCount) {
        AtomicInteger count = available.get(eventId + "-" + section);
        return count == null || count.get() >= seatCount;
    }
}
