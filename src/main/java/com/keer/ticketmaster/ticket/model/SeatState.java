package com.keer.ticketmaster.ticket.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SeatState {

    private String seatNumber;
    private Long eventId;
    private String section;
    private SeatStateStatus status;
    private String reservationId;
}
