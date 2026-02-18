package com.keer.ticketmaster.reservation.service;

import com.keer.ticketmaster.avro.ReservationCompletedEvent;
import com.keer.ticketmaster.reservation.dto.ReservationResponse;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.KeyQueryMetadata;
import org.apache.kafka.streams.state.HostInfo;
import org.apache.kafka.streams.state.ReadOnlyKeyValueStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.config.StreamsBuilderFactoryBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InteractiveQueryServiceTest {

    @Mock
    private StreamsBuilderFactoryBean streamsBuilderFactoryBean;

    @Mock
    private RestClient restClient;

    @Mock
    private KafkaStreams kafkaStreams;

    @Mock
    private ReadOnlyKeyValueStore<String, ReservationCompletedEvent> store;

    private InteractiveQueryService service;

    private static final String LOCAL_SERVER = "localhost:8080";
    private static final String RESERVATION_ID = "res-123";

    @BeforeEach
    void setUp() {
        service = new InteractiveQueryService(streamsBuilderFactoryBean, restClient);
        ReflectionTestUtils.setField(service, "applicationServer", LOCAL_SERVER);
    }

    private ReservationCompletedEvent buildCompletedEvent() {
        return ReservationCompletedEvent.newBuilder()
                .setReservationId(RESERVATION_ID)
                .setEventId(1L)
                .setUserId("user001")
                .setStatus("CONFIRMED")
                .setSection("A")
                .setSeatCount(2)
                .setAllocatedSeats(List.of("A-001", "A-002"))
                .setTimestamp(Instant.now().toEpochMilli())
                .build();
    }

    private void stubMetadata(KeyQueryMetadata metadata) {
        when(kafkaStreams.queryMetadataForKey(anyString(), anyString(), any(Serializer.class)))
                .thenReturn(metadata);
    }

    private KeyQueryMetadata localMetadata() {
        return new KeyQueryMetadata(new HostInfo("localhost", 8080), Set.of(), 0);
    }

    private KeyQueryMetadata remoteMetadata() {
        return new KeyQueryMetadata(new HostInfo("remote-host", 9090), Set.of(), 0);
    }

    private void stubStreamsRunning() {
        when(streamsBuilderFactoryBean.getKafkaStreams()).thenReturn(kafkaStreams);
        when(kafkaStreams.state()).thenReturn(KafkaStreams.State.RUNNING);
    }

    @Nested
    class QueryReservation {

        @Test
        void shouldThrowStoreNotReadyWhenKafkaStreamsIsNull() {
            when(streamsBuilderFactoryBean.getKafkaStreams()).thenReturn(null);

            assertThrows(StoreNotReadyException.class,
                    () -> service.queryReservation(RESERVATION_ID));
        }

        @Test
        void shouldThrowStoreNotReadyWhenKafkaStreamsNotRunning() {
            when(streamsBuilderFactoryBean.getKafkaStreams()).thenReturn(kafkaStreams);
            when(kafkaStreams.state()).thenReturn(KafkaStreams.State.REBALANCING);

            assertThrows(StoreNotReadyException.class,
                    () -> service.queryReservation(RESERVATION_ID));
        }

        @Test
        void shouldThrowStoreNotReadyWhenMetadataNotAvailable() {
            stubStreamsRunning();
            stubMetadata(KeyQueryMetadata.NOT_AVAILABLE);

            assertThrows(StoreNotReadyException.class,
                    () -> service.queryReservation(RESERVATION_ID));
        }

        @Test
        void shouldThrowStoreNotReadyWhenMetadataIsNull() {
            stubStreamsRunning();
            stubMetadata(null);

            assertThrows(StoreNotReadyException.class,
                    () -> service.queryReservation(RESERVATION_ID));
        }

        @Test
        void shouldQueryLocalStoreWhenHostIsLocal() {
            stubStreamsRunning();
            stubMetadata(localMetadata());
            when(kafkaStreams.store(any())).thenReturn(store);
            when(store.get(RESERVATION_ID)).thenReturn(buildCompletedEvent());

            ReservationResponse response = service.queryReservation(RESERVATION_ID);

            assertNotNull(response);
            assertEquals(RESERVATION_ID, response.getReservationId());
            assertEquals("CONFIRMED", response.getStatus());
            assertEquals("A", response.getSection());
            assertEquals(2, response.getSeatCount());
            assertEquals(List.of("A-001", "A-002"), response.getAllocatedSeats());
        }

        @Test
        void shouldReturnNullWhenLocalStoreHasNoEntry() {
            stubStreamsRunning();
            stubMetadata(localMetadata());
            when(kafkaStreams.store(any())).thenReturn(store);
            when(store.get(RESERVATION_ID)).thenReturn(null);

            ReservationResponse response = service.queryReservation(RESERVATION_ID);

            assertNull(response);
        }

        @SuppressWarnings("unchecked")
        @Test
        void shouldQueryRemoteStoreWhenHostIsRemote() {
            stubStreamsRunning();
            stubMetadata(remoteMetadata());

            ReservationResponse expected = ReservationResponse.builder()
                    .reservationId(RESERVATION_ID)
                    .status("CONFIRMED")
                    .build();

            var uriSpec = mock(RestClient.RequestHeadersUriSpec.class);
            var headersSpec = mock(RestClient.RequestHeadersSpec.class);
            var responseSpec = mock(RestClient.ResponseSpec.class);

            doReturn(uriSpec).when(restClient).get();
            doReturn(headersSpec).when(uriSpec).uri(anyString());
            doReturn(responseSpec).when(headersSpec).retrieve();
            when(responseSpec.body(ReservationResponse.class)).thenReturn(expected);

            ReservationResponse response = service.queryReservation(RESERVATION_ID);

            assertNotNull(response);
            assertEquals(RESERVATION_ID, response.getReservationId());
            assertEquals("CONFIRMED", response.getStatus());
            verify(uriSpec).uri("http://remote-host:9090/internal/reservations/" + RESERVATION_ID);
        }

        @SuppressWarnings("unchecked")
        @Test
        void shouldThrowRemoteQueryExceptionWhenRemoteFails() {
            stubStreamsRunning();
            stubMetadata(remoteMetadata());

            var uriSpec = mock(RestClient.RequestHeadersUriSpec.class);
            var headersSpec = mock(RestClient.RequestHeadersSpec.class);

            doReturn(uriSpec).when(restClient).get();
            doReturn(headersSpec).when(uriSpec).uri(anyString());
            doReturn(null).when(headersSpec).retrieve();
            when(headersSpec.retrieve()).thenThrow(new RuntimeException("Connection refused"));

            assertThrows(RemoteQueryException.class,
                    () -> service.queryReservation(RESERVATION_ID));
        }
    }

    @Nested
    class QueryLocalStore {

        @Test
        void shouldReturnNullWhenStoreThrowsException() {
            when(kafkaStreams.store(any())).thenThrow(new RuntimeException("Store unavailable"));

            ReservationResponse response = service.queryLocalStore(kafkaStreams, RESERVATION_ID);

            assertNull(response);
        }

        @Test
        void shouldMapAllFieldsCorrectly() {
            when(kafkaStreams.store(any())).thenReturn(store);
            when(store.get(RESERVATION_ID)).thenReturn(buildCompletedEvent());

            ReservationResponse response = service.queryLocalStore(kafkaStreams, RESERVATION_ID);

            assertNotNull(response);
            assertEquals(RESERVATION_ID, response.getReservationId());
            assertEquals(1L, response.getEventId());
            assertEquals("user001", response.getUserId());
            assertEquals("CONFIRMED", response.getStatus());
            assertEquals("A", response.getSection());
            assertEquals(2, response.getSeatCount());
            assertEquals(List.of("A-001", "A-002"), response.getAllocatedSeats());
            assertNotNull(response.getCreatedAt());
        }
    }
}
