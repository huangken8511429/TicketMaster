package com.keer.ticketmaster.booking.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class BookingRequest {
    private Long eventId;
    private String section;
    private int seatCount;
    private String userId;
}
