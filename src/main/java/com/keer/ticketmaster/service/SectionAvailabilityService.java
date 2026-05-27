package com.keer.ticketmaster.service;

import com.keer.ticketmaster.po.Event;
import com.keer.ticketmaster.po.Section;
import com.keer.ticketmaster.repository.EventRepository;
import com.keer.ticketmaster.response.SectionAvailabilityResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Aggregates section availability across Redis sub-partition counters,
 * derives the 5-level status, and answers both REST (one-shot) and SSE (streaming) callers.
 *
 * Status derivation lives here so frontend never needs to know the thresholds.
 * See specs/frontend-mvp/api-contract.md §4.1.
 */
@Service
@Profile({"api", "default"})
@RequiredArgsConstructor
@Slf4j
public class SectionAvailabilityService {

    // Status enum values (kept as String constants so SSE / REST can share them
    // without leaking enum classes through serialization).
    public static final String STATUS_NOT_STARTED = "NOT_STARTED";
    public static final String STATUS_PLENTY = "ON_SALE_PLENTY";
    public static final String STATUS_LIMITED = "ON_SALE_LIMITED";
    public static final String STATUS_FEW = "ON_SALE_FEW";
    public static final String STATUS_SOLD_OUT = "SOLD_OUT";

    // Thresholds (single source of truth, frontend never re-computes).
    private static final double THRESHOLD_PLENTY = 0.30;
    private static final double THRESHOLD_FEW = 0.05;

    private final EventRepository eventRepository;
    private final SeatAvailabilityRedisService redisService;

    private final Clock clock = Clock.systemDefaultZone();

    /**
     * One-shot fetch for {@code GET /api/events/{id}/sections}.
     *
     * @return list of section availability; empty list if event not found
     *         (controller decides whether to 404).
     */
    public List<SectionAvailabilityResponse> getSectionsForEvent(Long eventId) {
        Event event = eventRepository.findById(eventId).orElse(null);
        if (event == null || event.getSections() == null) {
            return List.of();
        }

        boolean notStarted = isBeforeSalesStart(event);

        List<SectionAvailabilityResponse> out = new ArrayList<>(event.getSections().size());
        for (Section section : event.getSections()) {
            int totalSeats = section.getRows() * section.getCols();
            int available = aggregateAvailableCount(eventId, section.getName(), totalSeats);
            String status = deriveStatus(notStarted, available, totalSeats);
            out.add(SectionAvailabilityResponse.builder()
                    .eventId(eventId)
                    .section(section.getName())
                    .totalSeats(totalSeats)
                    .availableCount(available)
                    .status(status)
                    .basePrice(section.getBasePrice())
                    .build());
        }
        return out;
    }

    /**
     * Lookup helper for the SSE bridge: only needs the event-level salesStartAt and basePrice
     * keyed by section name. Returns null if event not found.
     */
    public Event findEventForBridge(Long eventId) {
        return eventRepository.findById(eventId).orElse(null);
    }

    /**
     * Aggregate availableCount across all sub-partitions for a section.
     * Falls back to {@code totalSeats} if Redis has no sub-partition metadata yet
     * (event just created, status emitter has not fired).
     *
     * Returns -1 only as an internal "no data" sentinel; callers should treat negative
     * values as zero for downstream display.
     */
    public int aggregateAvailableCount(long eventId, String section, int totalSeatsFallback) {
        int subPartitions = redisService.getSubPartitionCount(eventId, section);
        if (subPartitions <= 0) {
            // No init yet — surface the full inventory so the frontend doesn't
            // mistakenly render SOLD_OUT before SectionInitProcessor runs.
            return totalSeatsFallback;
        }
        int sum = 0;
        for (int sp = 0; sp < subPartitions; sp++) {
            sum += redisService.getAvailableCount(eventId, section, sp);
        }
        return Math.max(sum, 0);
    }

    public boolean isBeforeSalesStart(Event event) {
        LocalDateTime salesStartAt = event.getSalesStartAt();
        if (salesStartAt == null) {
            return false; // legacy data → treat as immediately on sale
        }
        return LocalDateTime.now(clock).isBefore(salesStartAt);
    }

    public String deriveStatus(boolean notStarted, int availableCount, int totalSeats) {
        if (notStarted) {
            return STATUS_NOT_STARTED;
        }
        if (availableCount <= 0) {
            return STATUS_SOLD_OUT;
        }
        if (totalSeats <= 0) {
            // pathological — treat as plenty to avoid divide-by-zero
            return STATUS_PLENTY;
        }
        double ratio = (double) availableCount / (double) totalSeats;
        if (ratio > THRESHOLD_PLENTY) {
            return STATUS_PLENTY;
        }
        if (ratio > THRESHOLD_FEW) {
            return STATUS_LIMITED;
        }
        return STATUS_FEW;
    }
}
