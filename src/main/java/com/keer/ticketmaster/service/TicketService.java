package com.keer.ticketmaster.service;

import com.keer.ticketmaster.po.Event;
import com.keer.ticketmaster.repository.EventRepository;
import com.keer.ticketmaster.request.TicketRequest;
import com.keer.ticketmaster.response.TicketResponse;
import com.keer.ticketmaster.po.Seat;
import com.keer.ticketmaster.po.Ticket;
import com.keer.ticketmaster.repository.TicketRepository;
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
        ticket.setSeat(new Seat(request.getSection(), request.getRow(), request.getCol()));
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
     * Called after booking confirmation to invalidate stale cache.
     */
    @CacheEvict(value = "tickets:available", key = "#eventId")
    public void evictAvailableTicketsCache(Long eventId) {
        // Cache eviction is handled by Spring — method body is empty
    }

    private TicketResponse toResponse(Ticket ticket) {
        Seat seat = ticket.getSeat();
        return TicketResponse.builder()
                .id(ticket.getId())
                .eventId(ticket.getEvent().getId())
                .eventName(ticket.getEvent().getName())
                .section(seat != null ? seat.getSection() : null)
                .seatRow(seat != null ? seat.getSeatRow() : 0)
                .seatCol(seat != null ? seat.getSeatCol() : 0)
                .status(ticket.getStatus().name())
                .price(ticket.getPrice())
                .userId(ticket.getUserId())
                .build();
    }
}
