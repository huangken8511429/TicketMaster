package com.keer.ticketmaster.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TicketRequest {

    private Long eventId;

    private String section;

    private int row;

    private int col;

    private BigDecimal price;
}
