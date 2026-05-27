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
import java.util.concurrent.ExecutorService;

/**
 * Booking service — Kafka Streams pipeline only, no Redis.
 *
 * Mirrors ticket-master's pattern (Service.java:312-339):
 *   POST → produce BookingCommand → SeatAllocationProcessor → BookingCompletedEvent
 *   GET  → KTable Interactive Query; if not yet present, register a
 *          DeferredResult that BookingQueryTopology will resume via foreach.
 */
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
    private final ExecutorService virtualThreadExecutor;

    /**
     * Create a booking — fire-and-forget produce to {@link Topic#BOOKING_COMMANDS}.
     *
     * sub-partition routing is left to the processor (targetSubPartition=0
     * fans out to the only sub-partition under the default init). A hash-based
     * scheme can be reintroduced if multi-sub-partition init becomes the norm.
     */
    public String createBooking(BookingRequest request) {
        String bookingId = UUID.randomUUID().toString();

        BookingCommand command = BookingCommand.newBuilder()
                .setBookingId(bookingId)
                .setEventId(request.getEventId())
                .setSection(request.getSection())
                .setSeatCount(request.getSeatCount())
                .setUserId(request.getUserId())
                .setTargetSubPartition(0)
                .setTimestamp(Instant.now().toEpochMilli())
                .build();

        kafkaTemplate.send(Topic.BOOKING_COMMANDS, bookingId, command);

        return bookingId;
    }

    /**
     * Long-polling GET. Pure KTable interactive query — no Redis.
     *
     * 1. Find the partition owner; if remote, forward via HTTP.
     * 2. If local KTable already has the result, resume immediately.
     * 3. Otherwise register a pending DeferredResult that BookingQueryTopology
     *    will resume via foreach when the booking-completed record lands.
     */
    public DeferredResult<ResponseEntity<BookingResponse>> getBookingAsync(String bookingId) {
        DeferredResult<ResponseEntity<BookingResponse>> deferred = new DeferredResult<>(POLL_TIMEOUT_MS);

        HostInfo keyOwner = interactiveQueryService.getKeyOwner(bookingId);

        if (keyOwner != null && !interactiveQueryService.isLocal(keyOwner)) {
            forwardToRemote(keyOwner, bookingId, deferred);
            return deferred;
        }

        BookingResponse existing = queryLocal(bookingId);
        if (existing != null) {
            deferred.setResult(ResponseEntity.ok(existing));
            return deferred;
        }

        pendingRequests.register(bookingId, deferred);

        // Re-check after registration to close the race with the stream thread.
        existing = queryLocal(bookingId);
        if (existing != null) {
            deferred.setResult(ResponseEntity.ok(existing));
        }

        return deferred;
    }

    public BookingResponse queryBooking(String bookingId) {
        try {
            return interactiveQueryService.queryBooking(bookingId);
        } catch (StoreNotReadyException e) {
            log.debug("Store not ready when querying booking {}: {}", bookingId, e.getMessage());
            return null;
        }
    }

    public void waitLocally(String bookingId, DeferredResult<ResponseEntity<BookingResponse>> deferred) {
        BookingResponse response = queryLocal(bookingId);
        if (response != null) {
            deferred.setResult(ResponseEntity.ok(response));
            return;
        }

        pendingRequests.register(bookingId, deferred);

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

        virtualThreadExecutor.execute(() -> {
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
