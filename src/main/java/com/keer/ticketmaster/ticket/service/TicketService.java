package com.keer.ticketmaster.ticket.service;

import com.keer.ticketmaster.avro.SeatEvent;
import com.keer.ticketmaster.avro.SeatStateStatus;
import com.keer.ticketmaster.config.KafkaConstants;
import com.keer.ticketmaster.event.model.Event;
import com.keer.ticketmaster.event.repository.EventRepository;
import com.keer.ticketmaster.ticket.dto.TicketRequest;
import com.keer.ticketmaster.ticket.dto.TicketResponse;
import com.keer.ticketmaster.ticket.model.Ticket;
import com.keer.ticketmaster.ticket.repository.TicketRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
@Profile({"api", "default"})
@RequiredArgsConstructor
public class TicketService {

    private final TicketRepository ticketRepository;
    private final EventRepository eventRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;

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

        String section = extractSection(saved.getSeatNumber());
        SeatEvent seatEvent = SeatEvent.newBuilder()
                .setEventId(event.getId())
                .setSeatNumber(saved.getSeatNumber())
                .setSection(section)
                .setStatus(SeatStateStatus.AVAILABLE)
                .setTimestamp(Instant.now().toEpochMilli())
                .build();

        String eventKey = event.getId().toString();
        kafkaTemplate.send(KafkaConstants.TOPIC_SEAT_EVENTS, eventKey, seatEvent);

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

    public List<TicketResponse> getAvailableTicketsByEvent(Long eventId) {
        return ticketRepository.findByEventIdAndStatus(eventId, Ticket.TicketStatus.AVAILABLE).stream()
                .map(this::toResponse)
                .toList();
    }

    // Seat format: "A-001" → section "A"
    private String extractSection(String seatNumber) {
        int dash = seatNumber.indexOf('-');
        return dash > 0 ? seatNumber.substring(0, dash) : seatNumber;
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
