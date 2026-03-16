package com.keer.ticketmaster.reservation.service;

import com.keer.ticketmaster.avro.ReservationCommand;
import com.keer.ticketmaster.config.KafkaConstants;
import com.keer.ticketmaster.reservation.dto.ReservationRequest;
import com.keer.ticketmaster.reservation.dto.ReservationResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.streams.state.HostInfo;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.async.DeferredResult;

import java.time.Instant;
import java.util.UUID;

@Service
@Profile({"api", "default"})
@RequiredArgsConstructor
@Slf4j
public class ReservationService {

    private static final long ASYNC_TIMEOUT_MS = 10_000;

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final InteractiveQueryService interactiveQueryService;
    private final ReservationPendingRequests pendingRequests;
    private final RestClient restClient;

    public record CreateResult(String reservationId, String status) {}

    public CreateResult createReservation(ReservationRequest request) {
        String reservationId = UUID.randomUUID().toString();

        ReservationCommand command = ReservationCommand.newBuilder()
                .setReservationId(reservationId)
                .setEventId(request.getEventId())
                .setSection(request.getSection())
                .setSeatCount(request.getSeatCount())
                .setUserId(request.getUserId())
                .setTimestamp(Instant.now().toEpochMilli())
                .build();

        // Key = reservationId; Reservation Processor handles pre-filter and re-key
        kafkaTemplate.send(KafkaConstants.TOPIC_RESERVATION_COMMANDS, reservationId, command);

        return new CreateResult(reservationId, null);
    }

    public DeferredResult<ResponseEntity<ReservationResponse>> getReservationAsync(String reservationId) {
        DeferredResult<ResponseEntity<ReservationResponse>> deferred = new DeferredResult<>(ASYNC_TIMEOUT_MS);

        HostInfo keyOwner = interactiveQueryService.getKeyOwner(reservationId);

        if (keyOwner == null || interactiveQueryService.isLocal(keyOwner)) {
            // Key is on this pod (or metadata unavailable) — register and wait locally
            waitLocally(reservationId, deferred);
        } else {
            // Key is on another pod — forward long-poll to the correct pod
            forwardToOwner(keyOwner, reservationId, deferred);
        }

        return deferred;
    }

    public void waitLocally(String reservationId, DeferredResult<ResponseEntity<ReservationResponse>> deferred) {
        ReservationResponse response = queryLocalStore(reservationId);
        if (response != null) {
            deferred.setResult(ResponseEntity.ok(response));
            return;
        }

        pendingRequests.register(reservationId, deferred);

        // Double-check: result may have arrived between the store read and registration
        response = queryLocalStore(reservationId);
        if (response != null) {
            deferred.setResult(ResponseEntity.ok(response));
        }
    }

    private void forwardToOwner(HostInfo owner, String reservationId,
                                DeferredResult<ResponseEntity<ReservationResponse>> deferred) {
        String url = "http://%s:%d/internal/reservations/%s".formatted(
                owner.host(), owner.port(), reservationId);

        Thread.startVirtualThread(() -> {
            try {
                ReservationResponse response = restClient.get()
                        .uri(url)
                        .retrieve()
                        .body(ReservationResponse.class);
                if (response != null) {
                    deferred.setResult(ResponseEntity.ok(response));
                } else {
                    deferred.setResult(ResponseEntity.accepted().build());
                }
            } catch (Exception e) {
                log.warn("Failed to forward reservation query to {}: {}", url, e.getMessage());
                deferred.setResult(ResponseEntity.accepted().build());
            }
        });
    }

    private ReservationResponse queryLocalStore(String reservationId) {
        try {
            return interactiveQueryService.queryReservation(reservationId);
        } catch (StoreNotReadyException e) {
            log.debug("Store not ready when querying reservation {}: {}", reservationId, e.getMessage());
            return null;
        }
    }
}
