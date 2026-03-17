package com.keer.ticketmaster.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TicketResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    private Long eventId;

    private String eventName;

    private String section;

    private int seatRow;

    private int seatCol;

    private String status;

    private BigDecimal price;

    private String userId;
}
