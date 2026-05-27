package com.keer.ticketmaster.request;

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
