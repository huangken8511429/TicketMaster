package com.keer.ticketmaster.ticket.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TicketResponse {

    private Long id;

    private Long eventId;

    private String eventName;

    private String seatNumber;

    private String status;

    private BigDecimal price;
}
