package com.keer.ticketmaster.event.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EventResponse {

    private Long id;

    private String name;

    private String description;

    private LocalDate eventDate;

    private Long venueId;

    private String venueName;
}
