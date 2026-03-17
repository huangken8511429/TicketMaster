package com.keer.ticketmaster.booking.service;

import com.keer.ticketmaster.avro.BookingCompletedEvent;
import com.keer.ticketmaster.booking.dto.BookingResponse;
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
    private ReadOnlyKeyValueStore<String, BookingCompletedEvent> store;

    private InteractiveQueryService service;

    private static final String LOCAL_SERVER = "localhost:8080";
    private static final String BOOKING_ID = "booking-123";

    @BeforeEach
    void setUp() {
        service = new InteractiveQueryService(streamsBuilderFactoryBean, restClient);
        ReflectionTestUtils.setField(service, "applicationServer", LOCAL_SERVER);
    }

    private BookingCompletedEvent buildCompletedEvent() {
        return BookingCompletedEvent.newBuilder()
                .setBookingId(BOOKING_ID)
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
    class QueryBooking {

        @Test
        void shouldThrowStoreNotReadyWhenKafkaStreamsIsNull() {
            when(streamsBuilderFactoryBean.getKafkaStreams()).thenReturn(null);

            assertThrows(StoreNotReadyException.class,
                    () -> service.queryBooking(BOOKING_ID));
        }

        @Test
        void shouldThrowStoreNotReadyWhenKafkaStreamsNotRunning() {
            when(streamsBuilderFactoryBean.getKafkaStreams()).thenReturn(kafkaStreams);
            when(kafkaStreams.state()).thenReturn(KafkaStreams.State.REBALANCING);

            assertThrows(StoreNotReadyException.class,
                    () -> service.queryBooking(BOOKING_ID));
        }

        @Test
        void shouldThrowStoreNotReadyWhenMetadataNotAvailable() {
            stubStreamsRunning();
            stubMetadata(KeyQueryMetadata.NOT_AVAILABLE);

            assertThrows(StoreNotReadyException.class,
                    () -> service.queryBooking(BOOKING_ID));
        }

        @Test
        void shouldThrowStoreNotReadyWhenMetadataIsNull() {
            stubStreamsRunning();
            stubMetadata(null);

            assertThrows(StoreNotReadyException.class,
                    () -> service.queryBooking(BOOKING_ID));
        }

        @Test
        void shouldQueryLocalStoreWhenHostIsLocal() {
            stubStreamsRunning();
            stubMetadata(localMetadata());
            when(kafkaStreams.store(any())).thenReturn(store);
            when(store.get(BOOKING_ID)).thenReturn(buildCompletedEvent());

            BookingResponse response = service.queryBooking(BOOKING_ID);

            assertNotNull(response);
            assertEquals(BOOKING_ID, response.getBookingId());
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
            when(store.get(BOOKING_ID)).thenReturn(null);

            BookingResponse response = service.queryBooking(BOOKING_ID);

            assertNull(response);
        }

        @SuppressWarnings("unchecked")
        @Test
        void shouldQueryRemoteStoreWhenHostIsRemote() {
            stubStreamsRunning();
            stubMetadata(remoteMetadata());

            BookingResponse expected = BookingResponse.builder()
                    .bookingId(BOOKING_ID)
                    .status("CONFIRMED")
                    .build();

            var uriSpec = mock(RestClient.RequestHeadersUriSpec.class);
            var headersSpec = mock(RestClient.RequestHeadersSpec.class);
            var responseSpec = mock(RestClient.ResponseSpec.class);

            doReturn(uriSpec).when(restClient).get();
            doReturn(headersSpec).when(uriSpec).uri(anyString());
            doReturn(responseSpec).when(headersSpec).retrieve();
            when(responseSpec.body(BookingResponse.class)).thenReturn(expected);

            BookingResponse response = service.queryBooking(BOOKING_ID);

            assertNotNull(response);
            assertEquals(BOOKING_ID, response.getBookingId());
            assertEquals("CONFIRMED", response.getStatus());
            verify(uriSpec).uri("http://remote-host:9090/internal/bookings/" + BOOKING_ID);
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
                    () -> service.queryBooking(BOOKING_ID));
        }
    }

    @Nested
    class QueryLocalStore {

        @Test
        void shouldReturnNullWhenStoreThrowsException() {
            when(kafkaStreams.store(any())).thenThrow(new RuntimeException("Store unavailable"));

            BookingResponse response = service.queryLocalStore(kafkaStreams, BOOKING_ID);

            assertNull(response);
        }

        @Test
        void shouldMapAllFieldsCorrectly() {
            when(kafkaStreams.store(any())).thenReturn(store);
            when(store.get(BOOKING_ID)).thenReturn(buildCompletedEvent());

            BookingResponse response = service.queryLocalStore(kafkaStreams, BOOKING_ID);

            assertNotNull(response);
            assertEquals(BOOKING_ID, response.getBookingId());
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
