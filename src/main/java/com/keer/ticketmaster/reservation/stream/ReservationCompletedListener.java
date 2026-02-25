package com.keer.ticketmaster.reservation.stream;

import com.keer.ticketmaster.avro.ReservationCompletedEvent;
import com.keer.ticketmaster.reservation.service.ReservationPendingRequests;
import com.keer.ticketmaster.ticket.service.TicketService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@Profile({"api", "default"})
@RequiredArgsConstructor
@Slf4j
public class ReservationCompletedListener {

    private final ReservationPendingRequests pendingRequests;
    private final TicketService ticketService;

    @KafkaListener(
            topics = "reservation-completed",
            groupId = "${app.reservation.notify.group-id}",
            properties = {
                    "value.deserializer=io.confluent.kafka.serializers.KafkaAvroDeserializer",
                    "schema.registry.url=${spring.kafka.streams.properties[schema.registry.url]}",
                    "specific.avro.reader=true"
            }
    )
    public void onReservationCompleted(ConsumerRecord<String, ReservationCompletedEvent> record) {
        ReservationCompletedEvent event = record.value();
        log.debug("Received reservation-completed event: reservationId={}, status={}",
                event.getReservationId(), event.getStatus());
        pendingRequests.resolve(event);

        // Evict tickets cache when reservation is confirmed (inventory changed)
        if ("CONFIRMED".equalsIgnoreCase(event.getStatus())) {
            ticketService.evictAvailableTicketsCache(event.getEventId());
            log.debug("Evicted tickets cache for eventId={}", event.getEventId());
        }
    }
}
