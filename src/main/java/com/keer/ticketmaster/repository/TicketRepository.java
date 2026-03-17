package com.keer.ticketmaster.repository;

import com.keer.ticketmaster.po.Ticket;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TicketRepository extends JpaRepository<Ticket, Long> {

    List<Ticket> findByEventId(Long eventId);

    List<Ticket> findByEventIdAndStatus(Long eventId, Ticket.TicketStatus status);

    List<Ticket> findByEventIdAndSeatSectionAndSeatSeatRowAndSeatSeatCol(Long eventId, String section, int seatRow, int seatCol);
}
