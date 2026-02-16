package com.keer.ticketmaster.reservation.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReservationResponse {

    private String reservationId;

    private Long eventId;

    private String section;

    private int seatCount;

    private String userId;

    private String status;

    private List<String> allocatedSeats;

    private Instant createdAt;
}
