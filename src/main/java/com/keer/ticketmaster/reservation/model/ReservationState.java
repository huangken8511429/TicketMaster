package com.keer.ticketmaster.reservation.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReservationState {

    private String reservationId;
    private Long eventId;
    private String section;
    private int seatCount;
    private String userId;
    private String status; // PENDING, CONFIRMED, REJECTED
    private List<String> allocatedSeats;
    private Instant createdAt;
    private Instant updatedAt;
}
