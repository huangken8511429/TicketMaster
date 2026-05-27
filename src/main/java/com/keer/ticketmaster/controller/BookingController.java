package com.keer.ticketmaster.controller;

import com.keer.ticketmaster.request.BookingRequest;
import com.keer.ticketmaster.response.BookingResponse;
import com.keer.ticketmaster.service.BookingService;
import com.keer.ticketmaster.exception.RemoteQueryException;
import com.keer.ticketmaster.exception.StoreNotReadyException;
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
    public ResponseEntity<Map<String, String>> createBooking(@RequestBody BookingRequest request) {
        String bookingId = bookingService.createBooking(request);
        return ResponseEntity.accepted()
                .body(Map.of("bookingId", bookingId));
    }

    @GetMapping("/api/bookings/{bookingId}")
    public DeferredResult<ResponseEntity<BookingResponse>> getBooking(@PathVariable String bookingId) {
        return bookingService.getBookingAsync(bookingId);
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
