package com.keer.ticketmaster.reservation.repository;

import com.keer.ticketmaster.reservation.model.Reservation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    Optional<Reservation> findByTicketIdAndUserId(Long ticketId, String userId);

    boolean existsByTicketIdAndStatusNot(Long ticketId, Reservation.ReservationStatus status);
}
