package com.keer.ticketmaster.event.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class EventRequest {

    private String name;

    private String description;

    private LocalDate eventDate;

    private Long venueId;

    private List<AreaRequest> areas;
}
