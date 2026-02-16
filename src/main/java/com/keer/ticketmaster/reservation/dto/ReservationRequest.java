package com.keer.ticketmaster.reservation.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReservationRequest {

    private Long eventId;

    private String section;

    private int seatCount;

    private String userId;
}
