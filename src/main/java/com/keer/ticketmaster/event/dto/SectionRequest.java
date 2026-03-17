package com.keer.ticketmaster.event.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SectionRequest {
    private String name;
    private int rows;
    private int seatsPerRow;
}
