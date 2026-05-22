package com.keer.ticketmaster.response;

import com.keer.ticketmaster.po.BookingMode;
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

    /**
     * When ticket sales open. Null = legacy data, frontend treats as immediately on sale.
     * Phase 1 frontend MVP: see specs/frontend-mvp/api-contract.md §4.3.
     */
    private LocalDateTime salesStartAt;

    private Long venueId;

    private String venueName;

    private String performerName;

    private Integer totalSeats;

    private Integer sectionCount;

    /**
     * Booking flow used by the frontend for this event. Added in Phase A of seat-map
     * (see specs/seat-map/booking-mode-design.md).
     * Default SECTION_TEXT — backend fallback for any null entity value.
     */
    private BookingMode bookingMode;
}
