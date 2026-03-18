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

    private static final long POLL_TIMEOUT_MS = 10_000;

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final InteractiveQueryService interactiveQueryService;
    private final BookingPendingRequests pendingRequests;
    private final RestClient restClient;

    public String createBooking(BookingRequest request) {
        String bookingId = UUID.randomUUID().toString();

        BookingCommand command = BookingCommand.newBuilder()
                .setBookingId(bookingId)
                .setEventId(request.getEventId())
                .setSection(request.getSection())
                .setSeatCount(request.getSeatCount())
                .setUserId(request.getUserId())
                .setTimestamp(Instant.now().toEpochMilli())
                .build();

        kafkaTemplate.send(Topic.BOOKING_COMMANDS, bookingId, command);

        return bookingId;
    }

    /**
     * Long-polling: check state store first, if not ready, wait for Kafka Streams
     * to process the result via BookingPendingRequests.
     */
    public DeferredResult<ResponseEntity<BookingResponse>> getBookingAsync(String bookingId) {
        DeferredResult<ResponseEntity<BookingResponse>> deferred = new DeferredResult<>(POLL_TIMEOUT_MS);

        HostInfo keyOwner = interactiveQueryService.getKeyOwner(bookingId);

        if (keyOwner != null && !interactiveQueryService.isLocal(keyOwner)) {
            // Result partition is on another pod — forward long-poll
            forwardToRemote(keyOwner, bookingId, deferred);
            return deferred;
        }

        // Check state store — result may already be there
        BookingResponse existing = queryLocal(bookingId);
        if (existing != null) {
            deferred.setResult(ResponseEntity.ok(existing));
            return deferred;
        }

        // Not ready yet — register for notification
        pendingRequests.register(bookingId, deferred);

        // Double-check after registration to prevent race condition
        existing = queryLocal(bookingId);
        if (existing != null) {
            deferred.setResult(ResponseEntity.ok(existing));
        }

        return deferred;
    }

    /**
     * Synchronous read from state store (used by internal forwarding).
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
        BookingResponse response = queryLocal(bookingId);
        if (response != null) {
            deferred.setResult(ResponseEntity.ok(response));
            return;
        }

        pendingRequests.register(bookingId, deferred);

        // Double-check: result may have arrived between the store read and registration
        response = queryLocal(bookingId);
        if (response != null) {
            deferred.setResult(ResponseEntity.ok(response));
        }
    }

    private BookingResponse queryLocal(String bookingId) {
        try {
            return interactiveQueryService.queryBooking(bookingId);
        } catch (Exception e) {
            return null;
        }
    }

    private void forwardToRemote(HostInfo owner, String bookingId,
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
