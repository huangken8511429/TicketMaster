package com.keer.ticketmaster.reservation.service;

import com.keer.ticketmaster.avro.ReservationCompletedEvent;
import com.keer.ticketmaster.reservation.dto.ReservationResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.async.DeferredResult;

import java.util.concurrent.ConcurrentHashMap;

@Component
@Profile({"api", "default"})
@Slf4j
public class ReservationPendingRequests {

    private final ConcurrentHashMap<String, DeferredResult<ResponseEntity<ReservationResponse>>> pending =
            new ConcurrentHashMap<>();

    // Cache for results that arrive before DeferredResult is registered
    private final ConcurrentHashMap<String, ReservationResponse> earlyResults =
            new ConcurrentHashMap<>();

    public void register(String reservationId, DeferredResult<ResponseEntity<ReservationResponse>> deferred) {
        // Phase 1: check if result already arrived
        ReservationResponse early = earlyResults.remove(reservationId);
        if (early != null) {
            deferred.setResult(ResponseEntity.ok(early));
            return;
        }

        // Phase 2: register the DeferredResult
        pending.put(reservationId, deferred);

        // Phase 3: double-check — result may have arrived between phase 1 and phase 2
        early = earlyResults.remove(reservationId);
        if (early != null) {
            pending.remove(reservationId);
            deferred.setResult(ResponseEntity.ok(early));
            return;
        }

        deferred.onCompletion(() -> pending.remove(reservationId));
        deferred.onTimeout(() -> {
            pending.remove(reservationId);
            deferred.setResult(ResponseEntity.accepted().build());
        });
    }

    public void resolve(ReservationCompletedEvent event) {
        String reservationId = event.getReservationId();
        ReservationResponse response = toResponse(event);

        // Phase 1: try to resolve pending DeferredResult
        DeferredResult<ResponseEntity<ReservationResponse>> deferred = pending.remove(reservationId);
        if (deferred != null) {
            deferred.setResult(ResponseEntity.ok(response));
            log.debug("Resolved pending request for reservation {}", reservationId);
            return;
        }

        // Phase 2: DeferredResult not registered yet — cache for later pickup
        earlyResults.put(reservationId, response);

        // Phase 3: double-check — DeferredResult may have been registered between phase 1 and phase 2
        deferred = pending.remove(reservationId);
        if (deferred != null) {
            earlyResults.remove(reservationId);
            deferred.setResult(ResponseEntity.ok(response));
            log.debug("Resolved pending request (late) for reservation {}", reservationId);
        }
    }

    /**
     * Cleanup stale early results that were never picked up (e.g. pre-filter rejections
     * where the client never sends a GET).
     */
    @Scheduled(fixedDelay = 60_000)
    public void cleanupEarlyResults() {
        int size = earlyResults.size();
        if (size > 0) {
            earlyResults.clear();
            log.debug("Cleaned up {} stale early results", size);
        }
    }

    private ReservationResponse toResponse(ReservationCompletedEvent event) {
        return ReservationResponse.builder()
                .reservationId(event.getReservationId())
                .eventId(event.getEventId())
                .section(event.getSection())
                .seatCount(event.getSeatCount())
                .userId(event.getUserId())
                .status(event.getStatus())
                .allocatedSeats(event.getAllocatedSeats())
                .build();
    }
}
