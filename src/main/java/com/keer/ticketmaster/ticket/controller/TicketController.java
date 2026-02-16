package com.keer.ticketmaster.ticket.controller;

import com.keer.ticketmaster.ticket.dto.TicketRequest;
import com.keer.ticketmaster.ticket.dto.TicketResponse;
import com.keer.ticketmaster.ticket.service.TicketService;
import com.keer.ticketmaster.ticket.service.TicketSseService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

@RestController
@RequestMapping("/api/tickets")
@RequiredArgsConstructor
public class TicketController {

    private final TicketService ticketService;
    private final TicketSseService ticketSseService;

    @PostMapping
    public ResponseEntity<TicketResponse> createTicket(@RequestBody TicketRequest request) {
        TicketResponse response = ticketService.createTicket(request);
        if (response == null) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<TicketResponse> getTicket(@PathVariable Long id) {
        TicketResponse response = ticketService.getTicket(id);
        if (response == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(response);
    }

    @GetMapping
    public ResponseEntity<List<TicketResponse>> getTicketsByEvent(@RequestParam Long eventId) {
        List<TicketResponse> response = ticketService.getTicketsByEvent(eventId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/available")
    public ResponseEntity<List<TicketResponse>> getAvailableTicketsByEvent(@RequestParam Long eventId) {
        List<TicketResponse> response = ticketService.getAvailableTicketsByEvent(eventId);
        return ResponseEntity.ok(response);
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamTicketUpdates(@RequestParam Long eventId) {
        return ticketSseService.subscribe(eventId);
    }
}
