package com.keer.ticketmaster.reservation.service;

import com.keer.ticketmaster.avro.ReservationCompletedEvent;
import com.keer.ticketmaster.config.KafkaConstants;
import com.keer.ticketmaster.reservation.dto.ReservationResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.KeyQueryMetadata;
import org.apache.kafka.streams.StoreQueryParameters;
import org.apache.kafka.streams.state.HostInfo;
import org.apache.kafka.streams.state.QueryableStoreTypes;
import org.apache.kafka.streams.state.ReadOnlyKeyValueStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.config.StreamsBuilderFactoryBean;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Instant;

@Service
@Profile({"reservation-processor", "default"})
@RequiredArgsConstructor
@Slf4j
public class InteractiveQueryService implements ReservationQueryService {

    private final StreamsBuilderFactoryBean streamsBuilderFactoryBean;
    private final RestClient restClient;

    @Value("${spring.kafka.streams.properties[application.server]}")
    private String applicationServer;

    @Override
    public ReservationResponse queryReservation(String reservationId) {
        KafkaStreams kafkaStreams = streamsBuilderFactoryBean.getKafkaStreams();
        if (kafkaStreams == null || kafkaStreams.state() != KafkaStreams.State.RUNNING) {
            throw new StoreNotReadyException("Kafka Streams is not running");
        }

        KeyQueryMetadata metadata = kafkaStreams.queryMetadataForKey(
                KafkaConstants.RESERVATION_QUERY_STORE,
                reservationId,
                Serdes.String().serializer()
        );

        if (metadata == null || metadata.equals(KeyQueryMetadata.NOT_AVAILABLE)) {
            throw new StoreNotReadyException("Metadata not available for key: " + reservationId);
        }

        HostInfo activeHost = metadata.activeHost();
        if (isLocalHost(activeHost)) {
            return queryLocalStore(kafkaStreams, reservationId);
        } else {
            return queryRemoteStore(activeHost, reservationId);
        }
    }

    public ReservationResponse queryLocalStore(KafkaStreams kafkaStreams, String reservationId) {
        try {
            ReadOnlyKeyValueStore<String, ReservationCompletedEvent> store = kafkaStreams.store(
                    StoreQueryParameters.fromNameAndType(
                            KafkaConstants.RESERVATION_QUERY_STORE,
                            QueryableStoreTypes.keyValueStore()
                    )
            );

            ReservationCompletedEvent event = store.get(reservationId);
            if (event == null) {
                return null;
            }

            return toResponse(event);
        } catch (Exception e) {
            log.warn("Failed to query local store for reservation {}: {}", reservationId, e.getMessage());
            return null;
        }
    }

    private ReservationResponse queryRemoteStore(HostInfo hostInfo, String reservationId) {
        String url = "http://%s:%d/internal/reservations/%s".formatted(
                hostInfo.host(), hostInfo.port(), reservationId);
        try {
            return restClient.get()
                    .uri(url)
                    .retrieve()
                    .body(ReservationResponse.class);
        } catch (Exception e) {
            throw new RemoteQueryException(
                    "Failed to query remote instance %s:%d for reservation %s".formatted(
                            hostInfo.host(), hostInfo.port(), reservationId), e);
        }
    }

    private boolean isLocalHost(HostInfo hostInfo) {
        String[] parts = applicationServer.split(":");
        String localHost = parts[0];
        int localPort = Integer.parseInt(parts[1]);
        return hostInfo.host().equals(localHost) && hostInfo.port() == localPort;
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
                .createdAt(Instant.ofEpochMilli(event.getTimestamp()))
                .build();
    }
}
