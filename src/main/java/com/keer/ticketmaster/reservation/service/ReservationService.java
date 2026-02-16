package com.keer.ticketmaster.reservation.service;

import com.keer.ticketmaster.config.KafkaStreamsConfig;
import com.keer.ticketmaster.reservation.dto.ReservationRequest;
import com.keer.ticketmaster.reservation.dto.ReservationResponse;
import com.keer.ticketmaster.reservation.event.ReservationCommand;
import com.keer.ticketmaster.reservation.model.ReservationState;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StoreQueryParameters;
import org.apache.kafka.streams.state.QueryableStoreTypes;
import org.apache.kafka.streams.state.ReadOnlyKeyValueStore;
import org.springframework.kafka.config.StreamsBuilderFactoryBean;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ReservationService {

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final StreamsBuilderFactoryBean streamsBuilderFactoryBean;

    public String createReservation(ReservationRequest request) {
        String reservationId = UUID.randomUUID().toString();

        ReservationCommand command = new ReservationCommand(
                reservationId,
                request.getEventId(),
                request.getSection(),
                request.getSeatCount(),
                request.getUserId(),
                Instant.now()
        );

        kafkaTemplate.send(KafkaStreamsConfig.TOPIC_RESERVATION_COMMANDS, reservationId, command);

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

        return ReservationResponse.builder()
                .reservationId(state.getReservationId())
                .eventId(state.getEventId())
                .section(state.getSection())
                .seatCount(state.getSeatCount())
                .userId(state.getUserId())
                .status(state.getStatus())
                .allocatedSeats(state.getAllocatedSeats())
                .createdAt(state.getCreatedAt())
                .build();
    }
}
