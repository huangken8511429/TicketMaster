package com.keer.ticketmaster.controller;

import com.keer.ticketmaster.repository.EventRepository;
import com.keer.ticketmaster.response.SectionAvailabilityResponse;
import com.keer.ticketmaster.service.SectionAvailabilityService;
import com.keer.ticketmaster.service.SectionStatusSseService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * Frontend MVP — section availability endpoints (Phase 1 additions).
 *
 * <ul>
 *   <li>{@code GET /api/events/{id}/sections} — one-shot list with derived status</li>
 *   <li>{@code GET /api/events/{id}/sections/stream} — SSE bridge over Kafka section-status</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/events")
@Profile({"api", "default"})
@RequiredArgsConstructor
public class SectionController {

    private final SectionAvailabilityService availabilityService;
    private final SectionStatusSseService sseService;
    private final EventRepository eventRepository;

    @GetMapping("/{eventId}/sections")
    public ResponseEntity<List<SectionAvailabilityResponse>> getSections(@PathVariable Long eventId) {
        if (!eventRepository.existsById(eventId)) {
            return ResponseEntity.notFound().build();
        }
        List<SectionAvailabilityResponse> sections = availabilityService.getSectionsForEvent(eventId);
        return ResponseEntity.ok(sections);
    }

    @GetMapping(value = "/{eventId}/sections/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamSections(@PathVariable Long eventId, HttpServletResponse response) {
        if (!eventRepository.existsById(eventId)) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            // Return a completed emitter so async machinery still has something to consume.
            SseEmitter done = new SseEmitter(0L);
            done.complete();
            return done;
        }
        // Headers set on the underlying response are flushed synchronously,
        // which keeps the SSE handshake testable with standalone MockMvc.
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Cache-Control", "no-cache");
        return sseService.subscribe(eventId);
    }
}
