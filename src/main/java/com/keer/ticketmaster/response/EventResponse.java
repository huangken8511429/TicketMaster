package com.keer.ticketmaster.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EventResponse {

    private Long id;

    private String name;

    private String description;

    private LocalDateTime eventStartTime;

    private LocalDateTime eventEndTime;

    private Long venueId;

    private String venueName;

    private String performerName;

    private Integer totalSeats;
}
