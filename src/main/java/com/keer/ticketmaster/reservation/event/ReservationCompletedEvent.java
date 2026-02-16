package com.keer.ticketmaster.reservation.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReservationCompletedEvent {

    private String reservationId;
    private String userId;
    private String status;
    private List<String> allocatedSeats;
    private Instant timestamp;
}
