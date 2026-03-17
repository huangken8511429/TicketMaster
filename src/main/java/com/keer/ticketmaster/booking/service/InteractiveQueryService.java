package com.keer.ticketmaster.booking.service;

import com.keer.ticketmaster.avro.BookingCompletedEvent;
import com.keer.ticketmaster.config.StateStore;
import com.keer.ticketmaster.booking.dto.BookingResponse;
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
@Profile({"api", "default"})
@RequiredArgsConstructor
@Slf4j
public class InteractiveQueryService {

    private final StreamsBuilderFactoryBean streamsBuilderFactoryBean;
    private final RestClient restClient;

    @Value("${spring.kafka.streams.properties[application.server]}")
    private String applicationServer;

    public HostInfo getKeyOwner(String bookingId) {
        KafkaStreams kafkaStreams = streamsBuilderFactoryBean.getKafkaStreams();
        if (kafkaStreams == null || kafkaStreams.state() != KafkaStreams.State.RUNNING) {
            return null;
        }

        KeyQueryMetadata metadata = kafkaStreams.queryMetadataForKey(
                StateStore.BOOKING_QUERY,
                bookingId,
                Serdes.String().serializer()
        );

        if (metadata == null || metadata.equals(KeyQueryMetadata.NOT_AVAILABLE)) {
            return null;
        }

        return metadata.activeHost();
    }

    public boolean isLocal(HostInfo hostInfo) {
        return isLocalHost(hostInfo);
    }

    public BookingResponse queryBooking(String bookingId) {
        KafkaStreams kafkaStreams = streamsBuilderFactoryBean.getKafkaStreams();
        if (kafkaStreams == null || kafkaStreams.state() != KafkaStreams.State.RUNNING) {
            throw new StoreNotReadyException("Kafka Streams is not running");
        }

        KeyQueryMetadata metadata = kafkaStreams.queryMetadataForKey(
                StateStore.BOOKING_QUERY,
                bookingId,
                Serdes.String().serializer()
        );

        if (metadata == null || metadata.equals(KeyQueryMetadata.NOT_AVAILABLE)) {
            throw new StoreNotReadyException("Metadata not available for key: " + bookingId);
        }

        HostInfo activeHost = metadata.activeHost();
        if (isLocalHost(activeHost)) {
            return queryLocalStore(kafkaStreams, bookingId);
        } else {
            return queryRemoteStore(activeHost, bookingId);
        }
    }

    public BookingResponse queryLocalStore(KafkaStreams kafkaStreams, String bookingId) {
        try {
            ReadOnlyKeyValueStore<String, BookingCompletedEvent> store = kafkaStreams.store(
                    StoreQueryParameters.fromNameAndType(
                            StateStore.BOOKING_QUERY,
                            QueryableStoreTypes.keyValueStore()
                    )
            );

            BookingCompletedEvent event = store.get(bookingId);
            if (event == null) {
                return null;
            }

            return toResponse(event);
        } catch (Exception e) {
            log.warn("Failed to query local store for booking {}: {}", bookingId, e.getMessage());
            return null;
        }
    }

    private BookingResponse queryRemoteStore(HostInfo hostInfo, String bookingId) {
        String url = "http://%s:%d/internal/bookings/%s".formatted(
                hostInfo.host(), hostInfo.port(), bookingId);
        try {
            return restClient.get()
                    .uri(url)
                    .retrieve()
                    .body(BookingResponse.class);
        } catch (Exception e) {
            throw new RemoteQueryException(
                    "Failed to query remote instance %s:%d for booking %s".formatted(
                            hostInfo.host(), hostInfo.port(), bookingId), e);
        }
    }

    private boolean isLocalHost(HostInfo hostInfo) {
        String[] parts = applicationServer.split(":");
        String localHost = parts[0];
        int localPort = Integer.parseInt(parts[1]);
        return hostInfo.host().equals(localHost) && hostInfo.port() == localPort;
    }

    private BookingResponse toResponse(BookingCompletedEvent event) {
        return BookingResponse.builder()
                .bookingId(event.getBookingId())
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
