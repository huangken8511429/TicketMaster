package com.keer.ticketmaster.reservation.controller;

import com.keer.ticketmaster.reservation.dto.ReservationRequest;
import com.keer.ticketmaster.reservation.dto.ReservationResponse;
import com.keer.ticketmaster.reservation.service.ReservationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/reservations")
@RequiredArgsConstructor
public class ReservationController {

    private final ReservationService reservationService;

    @PostMapping
    public ResponseEntity<Map<String, String>> createReservation(@RequestBody ReservationRequest request) {
        String reservationId = reservationService.createReservation(request);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(Map.of("reservationId", reservationId));
    }

    @GetMapping("/{reservationId}")
    public ResponseEntity<ReservationResponse> getReservation(@PathVariable String reservationId) {
        ReservationResponse response = reservationService.getReservation(reservationId);
        if (response == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(response);
    }
}
