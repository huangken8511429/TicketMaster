package com.keer.ticketmaster.service;

import com.keer.ticketmaster.avro.SectionStatusEvent;
import com.keer.ticketmaster.po.Event;
import com.keer.ticketmaster.po.Section;
import com.keer.ticketmaster.response.SectionAvailabilityResponse;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Bridge between {@code section-status} Kafka topic and HTTP SSE clients (frontend).
 *
 * Design:
 * - Each API replica subscribes the topic with a random consumer group id so
 *   every replica receives every message (broadcast).
 * - In-memory cache keyed by (eventId, section, subPartition) tracks the latest
 *   per-sub-partition availableCount. On each event we aggregate across all known
 *   sub-partitions for that section and push the aggregated SectionAvailabilityResponse
 *   to all subscribed SseEmitters for that eventId.
 * - Heartbeats every 15 seconds prevent intermediary proxies from dropping the
 *   connection.
 * - Emitter timeout is 30 minutes (also frontend EventSource will auto-reconnect).
 *
 * See specs/frontend-mvp/api-contract.md §4.2.
 */
@Service
@Profile({"api", "default"})
@RequiredArgsConstructor
@Slf4j
public class SectionStatusSseService {

    private static final Duration EMITTER_TIMEOUT = Duration.ofMinutes(30);
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(15);

    private final SectionAvailabilityService availabilityService;

    /** eventId → live SSE emitters. */
    private final ConcurrentHashMap<Long, CopyOnWriteArrayList<SseEmitter>> emitters = new ConcurrentHashMap<>();

    /**
     * Local accumulator: eventId → (section → (subPartition → availableCount)).
     * Memory bound = O(events × sections × subPartitions); small for MVP.
     */
    private final ConcurrentHashMap<Long, ConcurrentHashMap<String, ConcurrentHashMap<Integer, Integer>>> subPartitionState =
            new ConcurrentHashMap<>();

    private ScheduledExecutorService heartbeatExecutor;

    @Value("${ticketmaster.sse.heartbeat-interval-seconds:15}")
    private long heartbeatIntervalSeconds;

    @Value("${ticketmaster.sse.emitter-timeout-minutes:30}")
    private long emitterTimeoutMinutes;

    @PostConstruct
    void startHeartbeat() {
        heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "section-sse-heartbeat");
            t.setDaemon(true);
            return t;
        });
        long interval = heartbeatIntervalSeconds > 0 ? heartbeatIntervalSeconds : HEARTBEAT_INTERVAL.toSeconds();
        heartbeatExecutor.scheduleAtFixedRate(this::sendHeartbeats, interval, interval, TimeUnit.SECONDS);
    }

    @PreDestroy
    void shutdown() {
        if (heartbeatExecutor != null) {
            heartbeatExecutor.shutdownNow();
        }
    }

    /**
     * Register a new SSE client for the given event.
     * Caller (controller) returns this directly to Spring MVC.
     */
    public SseEmitter subscribe(Long eventId) {
        long timeoutMillis = (emitterTimeoutMinutes > 0 ? emitterTimeoutMinutes : EMITTER_TIMEOUT.toMinutes()) * 60_000L;
        SseEmitter emitter = new SseEmitter(timeoutMillis);

        emitters.computeIfAbsent(eventId, k -> new CopyOnWriteArrayList<>()).add(emitter);

        Runnable cleanup = () -> {
            CopyOnWriteArrayList<SseEmitter> list = emitters.get(eventId);
            if (list != null) {
                list.remove(emitter);
            }
        };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> cleanup.run());

        // Optional: send an immediate "connected" event so frontend can confirm bridge is alive.
        try {
            emitter.send(SseEmitter.event().name("connected").data("{}"));
        } catch (IOException e) {
            log.debug("Failed to send initial connected event: {}", e.getMessage());
        }
        return emitter;
    }

    /**
     * Kafka listener for section-status broadcasts.
     *
     * groupId uses {@code random.uuid} so each API replica gets its own group and
     * receives every partition (broadcast). Offset reset = latest to avoid
     * replaying historical traffic on cold start.
     */
    @KafkaListener(
            topics = "${ticketmaster.kafka.section-status-topic:section-status}",
            groupId = "sse-bridge-${random.uuid}",
            properties = {"auto.offset.reset=latest"}
    )
    public void onSectionStatus(SectionStatusEvent event) {
        try {
            handleEvent(event);
        } catch (Exception e) {
            log.warn("Failed to handle SectionStatusEvent: {}", e.getMessage());
        }
    }

    /** Visible for tests. */
    void handleEvent(SectionStatusEvent event) {
        long eventId = event.getEventId();
        String section = event.getSection();

        ConcurrentHashMap<String, ConcurrentHashMap<Integer, Integer>> sections =
                subPartitionState.computeIfAbsent(eventId, k -> new ConcurrentHashMap<>());
        ConcurrentHashMap<Integer, Integer> bySubPartition =
                sections.computeIfAbsent(section, k -> new ConcurrentHashMap<>());
        bySubPartition.put(event.getSubPartition(), event.getAvailableCount());

        CopyOnWriteArrayList<SseEmitter> list = emitters.get(eventId);
        if (list == null || list.isEmpty()) {
            return;
        }

        SectionAvailabilityResponse payload = aggregateForBroadcast(eventId, section, bySubPartition);
        broadcast(eventId, list, payload, event.getTimestamp());
    }

    private SectionAvailabilityResponse aggregateForBroadcast(
            long eventId, String section, Map<Integer, Integer> bySubPartition) {

        int aggregated = bySubPartition.values().stream().mapToInt(Integer::intValue).sum();

        // Best-effort lookup of totalSeats / basePrice from JPA.
        Integer totalSeats = null;
        Long basePrice = null;
        boolean notStarted = false;
        Event event = availabilityService.findEventForBridge(eventId);
        if (event != null && event.getSections() != null) {
            notStarted = availabilityService.isBeforeSalesStart(event);
            for (Section s : event.getSections()) {
                if (section.equals(s.getName())) {
                    totalSeats = s.getRows() * s.getCols();
                    basePrice = s.getBasePrice();
                    break;
                }
            }
        }
        int effectiveTotal = totalSeats != null ? totalSeats : Math.max(aggregated, 1);
        String status = availabilityService.deriveStatus(notStarted, aggregated, effectiveTotal);

        return SectionAvailabilityResponse.builder()
                .eventId(eventId)
                .section(section)
                .totalSeats(effectiveTotal)
                .availableCount(aggregated)
                .status(status)
                .basePrice(basePrice)
                .build();
    }

    private void broadcast(Long eventId, CopyOnWriteArrayList<SseEmitter> list,
                           SectionAvailabilityResponse payload, long timestampMs) {
        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event()
                        .name("section-status")
                        .id(String.valueOf(timestampMs))
                        .data(payload));
            } catch (IOException e) {
                list.remove(emitter);
            } catch (IllegalStateException e) {
                // emitter already completed
                list.remove(emitter);
            }
        }
    }

    private void sendHeartbeats() {
        for (Map.Entry<Long, CopyOnWriteArrayList<SseEmitter>> entry : emitters.entrySet()) {
            CopyOnWriteArrayList<SseEmitter> list = entry.getValue();
            for (SseEmitter emitter : list) {
                try {
                    emitter.send(SseEmitter.event().name("heartbeat").data("{}"));
                } catch (IOException e) {
                    list.remove(emitter);
                } catch (IllegalStateException e) {
                    list.remove(emitter);
                }
            }
        }
    }

    /** Visible for tests. */
    Map<Long, CopyOnWriteArrayList<SseEmitter>> emittersSnapshot() {
        return new HashMap<>(emitters);
    }

    /** Visible for tests — count emitters currently registered for an event. */
    public int emitterCount(Long eventId) {
        CopyOnWriteArrayList<SseEmitter> list = emitters.get(eventId);
        return list == null ? 0 : list.size();
    }

    /** Visible for tests — synthesise a status event without going through Kafka. */
    void publishForTest(SectionStatusEvent event) {
        handleEvent(event);
    }

    /** Visible for tests — clear cached sub-partition state. */
    void resetForTest() {
        subPartitionState.clear();
    }

    /** Visible for tests — unused suppression. */
    @SuppressWarnings("unused")
    private void touch(List<?> ignored) {}
}
