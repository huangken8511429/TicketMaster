package com.keer.ticketmaster.reservation.service;

import com.keer.ticketmaster.avro.ReservationCommand;
import com.keer.ticketmaster.avro.ReservationState;
import com.keer.ticketmaster.config.KafkaStreamsConfig;
import com.keer.ticketmaster.reservation.dto.ReservationRequest;
import com.keer.ticketmaster.reservation.dto.ReservationResponse;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StoreQueryParameters;
import org.apache.kafka.streams.state.QueryableStoreTypes;
import org.apache.kafka.streams.state.ReadOnlyKeyValueStore;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.config.StreamsBuilderFactoryBean;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.async.DeferredResult;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ReservationService {

    private static final long ASYNC_TIMEOUT_MS = 30_000;

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final StreamsBuilderFactoryBean streamsBuilderFactoryBean;
    private final ReservationPendingRequests pendingRequests;

    public String createReservation(ReservationRequest request) {
        String reservationId = UUID.randomUUID().toString();

        ReservationCommand command = ReservationCommand.newBuilder()
                .setReservationId(reservationId)
                .setEventId(request.getEventId())
                .setSection(request.getSection())
                .setSeatCount(request.getSeatCount())
                .setUserId(request.getUserId())
                .setTimestamp(Instant.now().toEpochMilli())
                .build();

        String eventKey = request.getEventId().toString();
        kafkaTemplate.send(KafkaStreamsConfig.TOPIC_RESERVATION_COMMANDS, eventKey, command);

        return reservationId;
    }

    public ReservationResponse getReservation(String reservationId) {
        KafkaStreams kafkaStreams = streamsBuilderFactoryBean.getKafkaStreams();
        if (kafkaStreams == null) {
            return null;
        }

        ReadOnlyKeyValueStore<String, ReservationState> store = kafkaStreams.store(
                StoreQueryParameters.fromNameAndType(
                        KafkaStreamsConfig.RESERVATION_STORE,
                        QueryableStoreTypes.keyValueStore()
                )
        );

        ReservationState state = store.get(reservationId);
        if (state == null) {
            return null;
        }

        return toResponse(state);
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

    private ReservationResponse toResponse(ReservationState state) {
        return ReservationResponse.builder()
                .reservationId(state.getReservationId())
                .eventId(state.getEventId())
                .section(state.getSection())
                .seatCount(state.getSeatCount())
                .userId(state.getUserId())
                .status(state.getStatus())
                .allocatedSeats(state.getAllocatedSeats())
                .createdAt(Instant.ofEpochMilli(state.getCreatedAt()))
                .build();
    }
}
