package com.keer.ticketmaster.booking.service;

import com.keer.ticketmaster.avro.BookingCompletedEvent;
import com.keer.ticketmaster.booking.dto.BookingResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.async.DeferredResult;

import java.util.concurrent.ConcurrentHashMap;

@Component
@Profile({"api", "default"})
@Slf4j
public class BookingPendingRequests {

    private final ConcurrentHashMap<String, DeferredResult<ResponseEntity<BookingResponse>>> pending =
            new ConcurrentHashMap<>();

    public void register(String bookingId, DeferredResult<ResponseEntity<BookingResponse>> deferred) {
        pending.put(bookingId, deferred);
        deferred.onCompletion(() -> pending.remove(bookingId));
        deferred.onTimeout(() -> {
            pending.remove(bookingId);
            deferred.setResult(ResponseEntity.accepted().build());
        });
    }

    public void resolve(BookingCompletedEvent event) {
        String bookingId = event.getBookingId();
        DeferredResult<ResponseEntity<BookingResponse>> deferred = pending.remove(bookingId);
        if (deferred != null) {
            deferred.setResult(ResponseEntity.ok(toResponse(event)));
            log.debug("Resolved pending request for booking {}", bookingId);
        }
    }

    private BookingResponse toResponse(BookingCompletedEvent event) {
        return BookingResponse.builder()
                .bookingId(event.getBookingId())
                .eventId(event.getEventId())
                .section(event.getSection())
                .seatCount(event.getSeatCount())
                .userId(event.getUserId())
                .status(event.getStatus())
                .allocatedSeats(event.getAllocatedSeats())
                .build();
    }
}
