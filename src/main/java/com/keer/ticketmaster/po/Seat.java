package com.keer.ticketmaster.po;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Embeddable
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Seat {

    @Column(name = "seat_section")
    private String section;

    @Column(name = "seat_row")
    private int seatRow;

    @Column(name = "seat_col")
    private int seatCol;
}
