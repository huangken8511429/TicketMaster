package com.keer.ticketmaster.booking.controller;

import com.keer.ticketmaster.booking.dto.BookingResponse;
import com.keer.ticketmaster.booking.service.BookingService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;

@RestController
@Profile({"api", "default"})
@RequiredArgsConstructor
public class InternalBookingController {

    private final BookingService bookingService;

    @GetMapping("/internal/bookings/{bookingId}")
    public DeferredResult<ResponseEntity<BookingResponse>> getBookingInternal(
            @PathVariable String bookingId) {
        DeferredResult<ResponseEntity<BookingResponse>> deferred = new DeferredResult<>(10_000L);
        bookingService.waitLocally(bookingId, deferred);
        return deferred;
    }
}
