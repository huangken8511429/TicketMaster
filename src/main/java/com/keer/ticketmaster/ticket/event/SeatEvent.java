package com.keer.ticketmaster.ticket.event;

import com.keer.ticketmaster.ticket.model.SeatStateStatus;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SeatEvent {

    private Long eventId;
    private String seatNumber;
    private String section;
    private SeatStateStatus status;
    private Instant timestamp;
}
