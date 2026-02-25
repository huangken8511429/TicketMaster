package com.keer.ticketmaster.ticket.service;

import com.keer.ticketmaster.event.model.Event;
import com.keer.ticketmaster.event.repository.EventRepository;
import com.keer.ticketmaster.ticket.dto.TicketRequest;
import com.keer.ticketmaster.ticket.dto.TicketResponse;
import com.keer.ticketmaster.ticket.model.Ticket;
import com.keer.ticketmaster.ticket.repository.TicketRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@Profile({"api", "default"})
@RequiredArgsConstructor
public class TicketService {

    private final TicketRepository ticketRepository;
    private final EventRepository eventRepository;

    public TicketResponse createTicket(TicketRequest request) {
        Event event = eventRepository.findById(request.getEventId()).orElse(null);
        if (event == null) {
            return null;
        }

        Ticket ticket = new Ticket();
        ticket.setEvent(event);
        ticket.setSeatNumber(request.getSeatNumber());
        ticket.setPrice(request.getPrice());
        ticket.setStatus(Ticket.TicketStatus.AVAILABLE);
        Ticket saved = ticketRepository.save(ticket);

        return toResponse(saved);
    }

    public TicketResponse getTicket(Long id) {
        return ticketRepository.findById(id)
                .map(this::toResponse)
                .orElse(null);
    }

    public List<TicketResponse> getTicketsByEvent(Long eventId) {
        return ticketRepository.findByEventId(eventId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Cacheable(value = "tickets:available", key = "#eventId")
    public List<TicketResponse> getAvailableTicketsByEvent(Long eventId) {
        return ticketRepository.findByEventIdAndStatus(eventId, Ticket.TicketStatus.AVAILABLE).stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * Evict cache for available tickets when inventory changes.
     * Called after reservation confirmation to invalidate stale cache.
     */
    @CacheEvict(value = "tickets:available", key = "#eventId")
    public void evictAvailableTicketsCache(Long eventId) {
        // Cache eviction is handled by Spring — method body is empty
    }

    private TicketResponse toResponse(Ticket ticket) {
        return TicketResponse.builder()
                .id(ticket.getId())
                .eventId(ticket.getEvent().getId())
                .eventName(ticket.getEvent().getName())
                .seatNumber(ticket.getSeatNumber())
                .status(ticket.getStatus().name())
                .price(ticket.getPrice())
                .build();
    }
}
