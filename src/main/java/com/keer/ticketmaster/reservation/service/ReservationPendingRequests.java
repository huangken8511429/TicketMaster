package com.keer.ticketmaster.reservation.service;

import com.keer.ticketmaster.avro.ReservationCompletedEvent;
import com.keer.ticketmaster.reservation.dto.ReservationResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.async.DeferredResult;

import java.util.concurrent.ConcurrentHashMap;

@Component
@Slf4j
public class ReservationPendingRequests {

    private final ConcurrentHashMap<String, DeferredResult<ResponseEntity<ReservationResponse>>> pending =
            new ConcurrentHashMap<>();

    public void register(String reservationId, DeferredResult<ResponseEntity<ReservationResponse>> deferred) {
        pending.put(reservationId, deferred);
        deferred.onCompletion(() -> pending.remove(reservationId));
        deferred.onTimeout(() -> {
            pending.remove(reservationId);
            deferred.setResult(ResponseEntity.accepted().build());
        });
    }

    public void resolve(ReservationCompletedEvent event) {
        DeferredResult<ResponseEntity<ReservationResponse>> deferred = pending.remove(event.getReservationId());
        if (deferred == null) {
            return;
        }

        ReservationResponse response = ReservationResponse.builder()
                .reservationId(event.getReservationId())
                .eventId(event.getEventId())
                .userId(event.getUserId())
                .status(event.getStatus())
                .allocatedSeats(event.getAllocatedSeats())
                .build();

        deferred.setResult(ResponseEntity.ok(response));
        log.debug("Resolved pending request for reservation {}", event.getReservationId());
    }
}
