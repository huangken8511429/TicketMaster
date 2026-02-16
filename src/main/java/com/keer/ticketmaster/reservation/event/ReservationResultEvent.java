package com.keer.ticketmaster.reservation.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReservationResultEvent {

    private String reservationId;
    private boolean success;
    private List<String> allocatedSeats;
    private String failureReason;
    private Instant timestamp;
}
