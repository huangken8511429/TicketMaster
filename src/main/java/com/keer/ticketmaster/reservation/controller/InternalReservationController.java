package com.keer.ticketmaster.reservation.controller;

import com.keer.ticketmaster.reservation.dto.ReservationResponse;
import com.keer.ticketmaster.reservation.service.InteractiveQueryService;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.streams.KafkaStreams;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.config.StreamsBuilderFactoryBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile({"reservation-processor", "default"})
@RequiredArgsConstructor
public class InternalReservationController {

    private final InteractiveQueryService interactiveQueryService;
    private final StreamsBuilderFactoryBean streamsBuilderFactoryBean;

    @GetMapping("/internal/reservations/{reservationId}")
    public ResponseEntity<ReservationResponse> getReservationInternal(@PathVariable String reservationId) {
        KafkaStreams kafkaStreams = streamsBuilderFactoryBean.getKafkaStreams();
        if (kafkaStreams == null || kafkaStreams.state() != KafkaStreams.State.RUNNING) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }

        ReservationResponse response = interactiveQueryService.queryLocalStore(kafkaStreams, reservationId);
        if (response == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(response);
    }
}
