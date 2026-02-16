package com.keer.ticketmaster.reservation.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReservationRequestedEvent {

    private String reservationId;
    private Long eventId;
    private String section;
    private int seatCount;
    private String userId;
    private Instant timestamp;
}
