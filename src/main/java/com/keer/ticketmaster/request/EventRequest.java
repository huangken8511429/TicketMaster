package com.keer.ticketmaster.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class EventRequest {

    private String name;

    private String description;

    private LocalDateTime eventStartTime;

    private LocalDateTime eventEndTime;

    private Long venueId;

    private Long performerId;

    private List<SectionRequest> sections;
}
