package com.keer.ticketmaster.reservation.service;

import com.keer.ticketmaster.reservation.dto.ReservationResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
@Profile("api")
@Slf4j
public class HttpReservationQueryService implements ReservationQueryService {

    private final RestClient restClient;
    private final String reservationProcessorUrl;

    public HttpReservationQueryService(
            RestClient restClient,
            @Value("${app.reservation-processor.url}") String reservationProcessorUrl) {
        this.restClient = restClient;
        this.reservationProcessorUrl = reservationProcessorUrl;
    }

    @Override
    public ReservationResponse queryReservation(String reservationId) {
        String url = reservationProcessorUrl + "/internal/reservations/" + reservationId;
        try {
            return restClient.get()
                    .uri(url)
                    .retrieve()
                    .body(ReservationResponse.class);
        } catch (Exception e) {
            log.warn("Failed to query reservation-processor for reservation {}: {}", reservationId, e.getMessage());
            throw new StoreNotReadyException("Reservation processor unavailable: " + e.getMessage());
        }
    }
}
