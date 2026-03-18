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
    private final SeatAvailabilityRedisService redisService;
    private final ExecutorService virtualThreadExecutor;

    /**
     * Create a booking with Redis pre-filter and sub-partition routing.
     *
     * Flow:
     * 1. Redis atomic find-and-decrement across sub-partitions
     * 2. If no sub-partition has enough seats → reject at API level (no Kafka)
     * 3. If found → set targetSubPartition on command → send to Kafka
     * 4. If Redis is unavailable → send with sub-partition 0 (fallback)
     */
    public String createBooking(BookingRequest request) {
        String bookingId = UUID.randomUUID().toString();

        int targetSubPartition = 0;
        try {
            // findAndDecrement returns -1 when sub-partition count is 0 (not initialized)
            // or when all sub-partitions are exhausted. Only reject if initialized.
            int subPartitions = redisService.getSubPartitionCount(request.getEventId(), request.getSection());
            if (subPartitions > 0) {
                int result = redisService.findAndDecrement(
                        request.getEventId(), request.getSection(), request.getSeatCount());
                if (result < 0) {
                    log.info("Booking {} rejected at API level: no seats available for event {} section {}",
                            bookingId, request.getEventId(), request.getSection());
                    return null;
                }
                targetSubPartition = result;
            }
            // else: Redis not initialized yet — fallback to sub-partition 0, let processor decide
        } catch (Exception e) {
            // Redis unavailable — fallback to sub-partition 0, let processor decide
            log.warn("Redis unavailable for pre-filter, falling back: {}", e.getMessage());
        }

        BookingCommand command = BookingCommand.newBuilder()
                .setBookingId(bookingId)
                .setEventId(request.getEventId())
                .setSection(request.getSection())
                .setSeatCount(request.getSeatCount())
                .setUserId(request.getUserId())
                .setTargetSubPartition(targetSubPartition)
                .setTimestamp(Instant.now().toEpochMilli())
                .build();

        kafkaTemplate.send(Topic.BOOKING_COMMANDS, bookingId, command);

        return bookingId;
    }

    /**
     * Long-polling with Redis cache layer.
     *
     * Query order:
     * 1. Redis booking cache (shared, fast, no cross-pod forwarding)
     * 2. Local KTable state store (fallback)
     * 3. Remote pod forwarding (last resort)
     * 4. Register for DeferredResult notification (long-poll)
     */
    public DeferredResult<ResponseEntity<BookingResponse>> getBookingAsync(String bookingId) {
        DeferredResult<ResponseEntity<BookingResponse>> deferred = new DeferredResult<>(POLL_TIMEOUT_MS);

        // 1. Check Redis cache first — avoids cross-pod forwarding entirely
        BookingResponse cached = redisService.getCachedBookingResult(bookingId);
        if (cached != null) {
            deferred.setResult(ResponseEntity.ok(cached));
            return deferred;
        }

        // 2. Check KTable state store (local or remote)
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

        // 3. Not ready — register for notification
        pendingRequests.register(bookingId, deferred);

        // Double-check after registration to prevent race condition
        existing = queryLocal(bookingId);
        if (existing != null) {
            deferred.setResult(ResponseEntity.ok(existing));
        }

        return deferred;
    }

    public BookingResponse queryBooking(String bookingId) {
        // Check Redis first
        BookingResponse cached = redisService.getCachedBookingResult(bookingId);
        if (cached != null) {
            return cached;
        }

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
