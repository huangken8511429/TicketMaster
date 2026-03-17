package com.keer.ticketmaster.booking.controller;

import com.keer.ticketmaster.booking.dto.BookingRequest;
import com.keer.ticketmaster.booking.dto.BookingResponse;
import com.keer.ticketmaster.booking.service.BookingService;
import com.keer.ticketmaster.booking.service.RemoteQueryException;
import com.keer.ticketmaster.booking.service.StoreNotReadyException;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.async.DeferredResult;

import java.util.Map;

@RestController
@Profile({"api", "default"})
@RequiredArgsConstructor
public class BookingController {

    private final BookingService bookingService;

    @PostMapping("/api/bookings")
    public DeferredResult<ResponseEntity<BookingResponse>> createBooking(@RequestBody BookingRequest request) {
        return bookingService.createBooking(request);
    }

    @GetMapping("/api/bookings/{bookingId}")
    public ResponseEntity<BookingResponse> getBooking(@PathVariable String bookingId) {
        BookingResponse response = bookingService.queryBooking(bookingId);
        if (response == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(response);
    }

    @ExceptionHandler(StoreNotReadyException.class)
    public ResponseEntity<Map<String, String>> handleStoreNotReady(StoreNotReadyException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(RemoteQueryException.class)
    public ResponseEntity<Map<String, String>> handleRemoteQueryError(RemoteQueryException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Map.of("error", e.getMessage()));
    }
}
