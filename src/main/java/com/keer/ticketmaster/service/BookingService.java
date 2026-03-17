package com.keer.ticketmaster.service;

import com.keer.ticketmaster.avro.BookingCommand;
import com.keer.ticketmaster.exception.StoreNotReadyException;
import com.keer.ticketmaster.config.Topic;
import com.keer.ticketmaster.request.BookingPendingRequests;
import com.keer.ticketmaster.request.BookingRequest;
import com.keer.ticketmaster.response.BookingResponse;
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
public class BookingService {

    private static final long ASYNC_TIMEOUT_MS = 10_000;

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final InteractiveQueryService interactiveQueryService;
    private final BookingPendingRequests pendingRequests;
    private final RestClient restClient;

    /**
     * Send booking command to Kafka and return DeferredResult that resolves
     * when the Kafka Streams topology processes the result.
     * Single round-trip: POST blocks until result is ready.
     */
    public DeferredResult<ResponseEntity<BookingResponse>> createBooking(BookingRequest request) {
        String bookingId = UUID.randomUUID().toString();

        DeferredResult<ResponseEntity<BookingResponse>> deferred = new DeferredResult<>(ASYNC_TIMEOUT_MS);

        // Register DeferredResult BEFORE sending to Kafka to avoid race condition
        HostInfo keyOwner = interactiveQueryService.getKeyOwner(bookingId);

        if (keyOwner == null || interactiveQueryService.isLocal(keyOwner)) {
            pendingRequests.register(bookingId, deferred);
        } else {
            // Result will arrive on another pod — forward long-poll after sending command
            deferForwardToOwner(keyOwner, bookingId, deferred);
        }

        // Send command to Kafka
        BookingCommand command = BookingCommand.newBuilder()
                .setBookingId(bookingId)
                .setEventId(request.getEventId())
                .setSection(request.getSection())
                .setSeatCount(request.getSeatCount())
                .setUserId(request.getUserId())
                .setTimestamp(Instant.now().toEpochMilli())
                .build();

        kafkaTemplate.send(Topic.BOOKING_COMMANDS, bookingId, command);

        return deferred;
    }

    /**
     * Synchronous read from state store via Interactive Query.
     * Used by GET endpoint — returns instantly, no long-polling.
     */
    public BookingResponse queryBooking(String bookingId) {
        try {
            return interactiveQueryService.queryBooking(bookingId);
        } catch (StoreNotReadyException e) {
            log.debug("Store not ready when querying booking {}: {}", bookingId, e.getMessage());
            return null;
        }
    }

    /**
     * Register DeferredResult locally and wait for Kafka Streams to resolve it.
     * Used by InternalBookingController for inter-pod forwarding.
     */
    public void waitLocally(String bookingId, DeferredResult<ResponseEntity<BookingResponse>> deferred) {
        BookingResponse response = queryBooking(bookingId);
        if (response != null) {
            deferred.setResult(ResponseEntity.ok(response));
            return;
        }

        pendingRequests.register(bookingId, deferred);

        // Double-check: result may have arrived between the store read and registration
        response = queryBooking(bookingId);
        if (response != null) {
            deferred.setResult(ResponseEntity.ok(response));
        }
    }

    private void deferForwardToOwner(HostInfo owner, String bookingId,
                                     DeferredResult<ResponseEntity<BookingResponse>> deferred) {
        String url = "http://%s:%d/internal/bookings/%s".formatted(
                owner.host(), owner.port(), bookingId);

        Thread.startVirtualThread(() -> {
            try {
                BookingResponse response = restClient.get()
                        .uri(url)
                        .retrieve()
                        .body(BookingResponse.class);
                if (response != null) {
                    deferred.setResult(ResponseEntity.ok(response));
                } else {
                    deferred.setResult(ResponseEntity.accepted().build());
                }
            } catch (Exception e) {
                log.warn("Failed to forward booking query to {}: {}", url, e.getMessage());
                deferred.setResult(ResponseEntity.accepted().build());
            }
        });
    }
}
