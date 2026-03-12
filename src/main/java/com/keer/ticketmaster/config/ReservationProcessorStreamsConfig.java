package com.keer.ticketmaster.config;

import com.keer.ticketmaster.avro.*;
import com.keer.ticketmaster.reservation.stream.SeatAllocationProcessor;
import com.keer.ticketmaster.reservation.stream.SectionInitProcessor;
import com.keer.ticketmaster.reservation.stream.SectionStatusEmitter;
import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.kstream.*;
import org.apache.kafka.streams.state.KeyValueStore;
import org.apache.kafka.streams.state.StoreBuilder;
import org.apache.kafka.streams.state.Stores;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.EnableKafkaStreams;

import java.util.Map;

@Configuration
@Profile({"event-processor", "reservation-processor", "default"})
@EnableKafkaStreams
public class ReservationProcessorStreamsConfig {

    @Value("${spring.kafka.streams.properties[schema.registry.url]}")
    private String schemaRegistryUrl;

    @Bean
    public KStream<String, ReservationCompletedEvent> reservationPipeline(StreamsBuilder builder) {

        Map<String, String> serdeConfig = Map.of("schema.registry.url", schemaRegistryUrl);

        SpecificAvroSerde<ReservationCommand> commandSerde = newAvroSerde(serdeConfig);
        SpecificAvroSerde<ReservationCompletedEvent> completedSerde = newAvroSerde(serdeConfig);
        SpecificAvroSerde<SectionInitCommand> sectionInitSerde = newAvroSerde(serdeConfig);
        SpecificAvroSerde<SectionSeatState> seatStateSerde = newAvroSerde(serdeConfig);
        SpecificAvroSerde<SectionStatusEvent> statusEventSerde = newAvroSerde(serdeConfig);

        // State store for seat inventory
        StoreBuilder<KeyValueStore<String, SectionSeatState>> seatStoreBuilder =
                Stores.keyValueStoreBuilder(
                        Stores.persistentKeyValueStore(KafkaConstants.SEAT_INVENTORY_STORE),
                        Serdes.String(),
                        seatStateSerde
                );
        builder.addStateStore(seatStoreBuilder);

        // --- Init path: section-init → SectionInitProcessor → section-status ---
        builder.stream(KafkaConstants.TOPIC_SECTION_INIT, Consumed.with(Serdes.String(), sectionInitSerde))
                .process(SectionInitProcessor::new, KafkaConstants.SEAT_INVENTORY_STORE)
                .mapValues((ValueMapper<SectionSeatState, SectionStatusEvent>) state ->
                        SectionStatusEvent.newBuilder()
                                .setEventId(state.getEventId())
                                .setSection(state.getSection())
                                .setAvailableCount(state.getAvailableCount())
                                .setTimestamp(System.currentTimeMillis())
                                .build())
                .to(KafkaConstants.TOPIC_SECTION_STATUS, Produced.with(Serdes.String(), statusEventSerde));

        // --- Allocation path: reservation-commands → SeatAllocationProcessor → reservation-completed ---
        var completedStream = builder.stream(KafkaConstants.TOPIC_RESERVATION_COMMANDS, Consumed.with(Serdes.String(), commandSerde))
                .process(SeatAllocationProcessor::new, KafkaConstants.SEAT_INVENTORY_STORE);

        completedStream.to(KafkaConstants.TOPIC_RESERVATION_COMPLETED, Produced.with(Serdes.String(), completedSerde));

        // --- Status update path: allocation results → SectionStatusEmitter → section-status ---
        completedStream
                .process(SectionStatusEmitter::new, KafkaConstants.SEAT_INVENTORY_STORE)
                .mapValues((ValueMapper<SectionSeatState, SectionStatusEvent>) state ->
                        SectionStatusEvent.newBuilder()
                                .setEventId(state.getEventId())
                                .setSection(state.getSection())
                                .setAvailableCount(state.getAvailableCount())
                                .setTimestamp(System.currentTimeMillis())
                                .build())
                .to(KafkaConstants.TOPIC_SECTION_STATUS, Produced.with(Serdes.String(), statusEventSerde));

        return null;
    }

    private static <T extends org.apache.avro.specific.SpecificRecord> SpecificAvroSerde<T> newAvroSerde(
            Map<String, String> serdeConfig) {
        SpecificAvroSerde<T> serde = new SpecificAvroSerde<>();
        serde.configure(serdeConfig, false);
        return serde;
    }
}
