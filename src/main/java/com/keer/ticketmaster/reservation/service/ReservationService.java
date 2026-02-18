package com.keer.ticketmaster.reservation.service;

import com.keer.ticketmaster.avro.ReservationCommand;
import com.keer.ticketmaster.avro.ReservationCompletedEvent;
import com.keer.ticketmaster.config.KafkaConstants;
import com.keer.ticketmaster.reservation.dto.ReservationRequest;
import com.keer.ticketmaster.reservation.dto.ReservationResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.async.DeferredResult;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@Profile({"api", "default"})
@RequiredArgsConstructor
@Slf4j
public class ReservationService {

    private static final long ASYNC_TIMEOUT_MS = 30_000;

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ReservationQueryService reservationQueryService;
    private final ReservationPendingRequests pendingRequests;
    private final SeatAvailabilityChecker availableSeatCache;

    public record CreateResult(String reservationId, String status) {}

    public CreateResult createReservation(ReservationRequest request) {
        String reservationId = UUID.randomUUID().toString();

        // Pre-filter: reject immediately if cache says not enough seats
        if (!availableSeatCache.hasEnoughSeats(request.getEventId(), request.getSection(), request.getSeatCount())) {
            ReservationCompletedEvent rejectedEvent = ReservationCompletedEvent.newBuilder()
                    .setReservationId(reservationId)
                    .setEventId(request.getEventId())
                    .setSection(request.getSection())
                    .setSeatCount(request.getSeatCount())
                    .setUserId(request.getUserId())
                    .setStatus("REJECTED")
                    .setAllocatedSeats(List.of())
                    .setTimestamp(Instant.now().toEpochMilli())
                    .build();

            kafkaTemplate.send(KafkaConstants.TOPIC_RESERVATION_COMPLETED,
                    request.getEventId().toString(), rejectedEvent);

            return new CreateResult(reservationId, "REJECTED");
        }

        ReservationCommand command = ReservationCommand.newBuilder()
                .setReservationId(reservationId)
                .setEventId(request.getEventId())
                .setSection(request.getSection())
                .setSeatCount(request.getSeatCount())
                .setUserId(request.getUserId())
                .setTimestamp(Instant.now().toEpochMilli())
                .build();

        String eventKey = request.getEventId().toString();
        kafkaTemplate.send(KafkaConstants.TOPIC_RESERVATION_COMMANDS, eventKey, command);

        return new CreateResult(reservationId, null);
    }

    public ReservationResponse getReservation(String reservationId) {
        try {
            return reservationQueryService.queryReservation(reservationId);
        } catch (StoreNotReadyException e) {
            log.debug("Store not ready when querying reservation {}: {}", reservationId, e.getMessage());
            return null;
        }
    }

    public DeferredResult<ResponseEntity<ReservationResponse>> getReservationAsync(String reservationId) {
        DeferredResult<ResponseEntity<ReservationResponse>> deferred = new DeferredResult<>(ASYNC_TIMEOUT_MS);

        ReservationResponse response = getReservation(reservationId);
        if (response != null && !"PENDING".equals(response.getStatus())) {
            deferred.setResult(ResponseEntity.ok(response));
            return deferred;
        }

        pendingRequests.register(reservationId, deferred);

        // Double-check: result may have arrived between the store read and registration
        response = getReservation(reservationId);
        if (response != null && !"PENDING".equals(response.getStatus())) {
            deferred.setResult(ResponseEntity.ok(response));
        }

        return deferred;
    }
}
