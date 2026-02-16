package com.keer.ticketmaster.venue.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class VenueRequest {

    private String name;

    private String address;

    private Integer capacity;
}
