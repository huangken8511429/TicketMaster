package com.keer.ticketmaster.ticket.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TicketRequest {

    private Long eventId;

    private String seatNumber;

    private BigDecimal price;
}
