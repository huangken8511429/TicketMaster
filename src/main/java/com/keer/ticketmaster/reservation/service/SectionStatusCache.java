package com.keer.ticketmaster.reservation.service;

import com.keer.ticketmaster.avro.SectionSeatState;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

@Component
@Profile({"api", "default"})
@Slf4j
public class SectionStatusCache implements SeatAvailabilityChecker {

    private final ConcurrentHashMap<String, Integer> availableCounts = new ConcurrentHashMap<>();

    @KafkaListener(
            topics = "section-status",
            groupId = "${app.section-status.group-id}",
            properties = {
                    "value.deserializer=io.confluent.kafka.serializers.KafkaAvroDeserializer",
                    "schema.registry.url=${spring.kafka.streams.properties[schema.registry.url]}",
                    "specific.avro.reader=true"
            }
    )
    public void onSectionStatus(ConsumerRecord<String, SectionSeatState> record) {
        SectionSeatState state = record.value();
        String key = state.getEventId() + "-" + state.getSection();
        availableCounts.put(key, state.getAvailableCount());
        log.debug("Section status updated: {}={}", key, state.getAvailableCount());
    }

    @Override
    public boolean hasEnoughSeats(long eventId, String section, int seatCount) {
        Integer count = availableCounts.get(eventId + "-" + section);
        // If no data yet, allow the request to go through (Kafka Streams will reject if truly unavailable)
        return count == null || count >= seatCount;
    }
}
