package com.keer.ticketmaster.reservation.service;

import com.keer.ticketmaster.reservation.dto.ReservationResponse;

public interface ReservationQueryService {

    ReservationResponse queryReservation(String reservationId);
}
