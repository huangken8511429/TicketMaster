package com.keer.ticketmaster.reservation.controller;

import com.keer.ticketmaster.reservation.dto.ReservationResponse;
import com.keer.ticketmaster.reservation.service.ReservationService;
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
public class InternalReservationController {

    private final ReservationService reservationService;

    @GetMapping("/internal/reservations/{reservationId}")
    public DeferredResult<ResponseEntity<ReservationResponse>> getReservationInternal(
            @PathVariable String reservationId) {
        DeferredResult<ResponseEntity<ReservationResponse>> deferred = new DeferredResult<>(10_000L);
        reservationService.waitLocally(reservationId, deferred);
        return deferred;
    }
}
