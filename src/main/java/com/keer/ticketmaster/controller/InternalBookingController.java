package com.keer.ticketmaster.controller;

import com.keer.ticketmaster.response.BookingResponse;
import com.keer.ticketmaster.service.BookingService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;

/**
 * Internal endpoint for cross-pod booking query forwarding.
 * Used by InteractiveQueryService when the booking state is on a remote API pod.
 *
 * This endpoint supports long-polling: if the result is not yet available,
 * it registers a DeferredResult and waits for the Kafka Streams topology to resolve it.
 */
@RestController
@Profile({"api", "default"})
@RequiredArgsConstructor
public class InternalBookingController {

    private final BookingService bookingService;

    @GetMapping("/internal/bookings/{bookingId}")
    public DeferredResult<ResponseEntity<BookingResponse>> getBooking(@PathVariable String bookingId) {
        DeferredResult<ResponseEntity<BookingResponse>> deferred = new DeferredResult<>(10_000L);
        bookingService.waitLocally(bookingId, deferred);
        return deferred;
    }
}
