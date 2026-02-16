package com.keer.ticketmaster.ticket.stream;

import com.keer.ticketmaster.reservation.event.ReservationCompletedEvent;
import com.keer.ticketmaster.ticket.dto.TicketResponse;
import com.keer.ticketmaster.ticket.model.Ticket;
import com.keer.ticketmaster.ticket.repository.TicketRepository;
import com.keer.ticketmaster.ticket.service.TicketSseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

// @Component — disabled: DB write-back will be re-implemented as event-driven
@RequiredArgsConstructor
@Slf4j
public class TicketStatusSyncListener {

    private final TicketRepository ticketRepository;
    private final TicketSseService ticketSseService;

    @KafkaListener(topics = "reservation-completed", groupId = "ticket-status-sync")
    @Transactional
    public void onReservationCompleted(ReservationCompletedEvent event) {
        if (!"CONFIRMED".equals(event.getStatus())) {
            return;
        }

        Long eventId = event.getEventId();
        List<String> seats = event.getAllocatedSeats();

        if (eventId == null || seats == null || seats.isEmpty()) {
            log.warn("Skipping reservation {} — missing eventId or seats", event.getReservationId());
            return;
        }

        List<Ticket> tickets = ticketRepository.findByEventIdAndSeatNumberIn(eventId, seats);

        List<TicketResponse> updated = new ArrayList<>();
        for (Ticket ticket : tickets) {
            if (ticket.getStatus() == Ticket.TicketStatus.AVAILABLE) {
                ticket.setStatus(Ticket.TicketStatus.RESERVED);
                updated.add(toResponse(ticket));
            }
        }

        ticketRepository.saveAll(tickets);
        log.info("Synced {} tickets to RESERVED for reservation {}", updated.size(), event.getReservationId());

        if (!updated.isEmpty()) {
            ticketSseService.broadcast(eventId, updated);
        }
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
