package com.keer.ticketmaster.reservation.service;

import com.keer.ticketmaster.avro.SectionStatusEvent;
import com.keer.ticketmaster.config.KafkaConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StoreQueryParameters;
import org.apache.kafka.streams.state.QueryableStoreTypes;
import org.apache.kafka.streams.state.ReadOnlyKeyValueStore;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.config.StreamsBuilderFactoryBean;
import org.springframework.stereotype.Component;

@Component
@Profile({"api", "default"})
@RequiredArgsConstructor
@Slf4j
public class SectionStatusCache implements SeatAvailabilityChecker {

    private final StreamsBuilderFactoryBean streamsBuilderFactoryBean;

    @Override
    public boolean hasEnoughSeats(long eventId, String section, int seatCount) {
        try {
            KafkaStreams kafkaStreams = streamsBuilderFactoryBean.getKafkaStreams();
            if (kafkaStreams == null || kafkaStreams.state() != KafkaStreams.State.RUNNING) {
                return true; // Store not ready — let Kafka Streams reject if truly unavailable
            }

            ReadOnlyKeyValueStore<String, SectionStatusEvent> store = kafkaStreams.store(
                    StoreQueryParameters.fromNameAndType(
                            KafkaConstants.SECTION_STATUS_STORE,
                            QueryableStoreTypes.keyValueStore()
                    )
            );

            String key = eventId + "-" + section;
            SectionStatusEvent status = store.get(key);

            if (status == null) {
                return true; // No data yet — allow through
            }

            return status.getAvailableCount() >= seatCount;
        } catch (Exception e) {
            log.debug("Failed to query section status store: {}", e.getMessage());
            return true; // On error — allow through, Kafka Streams will reject if needed
        }
    }
}
