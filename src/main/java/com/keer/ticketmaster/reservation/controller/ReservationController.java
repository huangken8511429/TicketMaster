package com.keer.ticketmaster.reservation.controller;

import com.keer.ticketmaster.reservation.dto.ReservationRequest;
import com.keer.ticketmaster.reservation.dto.ReservationResponse;
import com.keer.ticketmaster.reservation.service.RemoteQueryException;
import com.keer.ticketmaster.reservation.service.ReservationService;
import com.keer.ticketmaster.reservation.service.StoreNotReadyException;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.async.DeferredResult;

import java.util.HashMap;
import java.util.Map;

@RestController
@Profile({"api", "default"})
@RequiredArgsConstructor
public class ReservationController {

    private final ReservationService reservationService;

    @PostMapping("/api/reservations")
    public ResponseEntity<Map<String, String>> createReservation(@RequestBody ReservationRequest request) {
        ReservationService.CreateResult result = reservationService.createReservation(request);
        Map<String, String> body = new HashMap<>();
        body.put("reservationId", result.reservationId());
        if (result.status() != null) {
            body.put("status", result.status());
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(body);
    }

    @GetMapping("/api/reservations/{reservationId}")
    public DeferredResult<ResponseEntity<ReservationResponse>> getReservation(@PathVariable String reservationId) {
        return reservationService.getReservationAsync(reservationId);
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
