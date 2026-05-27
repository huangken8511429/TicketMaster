package com.keer.ticketmaster.response;

import com.keer.ticketmaster.avro.BookingCompletedEvent;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BookingResponse {
    private String bookingId;
    private Long eventId;
    private String section;
    private int seatCount;
    private String userId;
    private String status;
    private List<String> allocatedSeats;
    private Instant createdAt;

    public static BookingResponse fromEvent(BookingCompletedEvent event) {
        return BookingResponse.builder()
                .bookingId(event.getBookingId())
                .eventId(event.getEventId())
                .section(event.getSection())
                .seatCount(event.getSeatCount())
                .userId(event.getUserId())
                .status(event.getStatus())
                .allocatedSeats(event.getAllocatedSeats())
                .createdAt(Instant.ofEpochMilli(event.getTimestamp()))
                .build();
    }
}
