package com.keer.ticketmaster.streaming.topology;

import com.keer.ticketmaster.avro.*;
import com.keer.ticketmaster.config.StateStore;
import com.keer.ticketmaster.config.Topic;
import com.keer.ticketmaster.streaming.processor.SeatAllocationProcessor;
import com.keer.ticketmaster.streaming.processor.SectionInitProcessor;
import com.keer.ticketmaster.streaming.processor.SectionStatusEmitter;
import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.Produced;
import org.apache.kafka.streams.kstream.ValueMapper;
import org.apache.kafka.streams.state.KeyValueStore;
import org.apache.kafka.streams.state.StoreBuilder;
import org.apache.kafka.streams.state.Stores;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.util.Map;

/**
 * Seat Processor (tm-seat) topology.
 *
 * Consumes:
 *   - section-init          (key=eventId-section) → SectionInitProcessor → section-status
 *   - seat-allocation-requests (key=eventId-section) → SeatAllocationProcessor → seat-allocation-results
 *
 * Produces:
 *   - seat-allocation-results (key=bookingId)
 *   - section-status          (key=eventId-section)
 *
 * State store: seat-inventory-store (RocksDB)
 */
@Configuration
@Profile({"seat-processor", "default"})
public class SeatProcessorTopology {

    @Value("${spring.kafka.streams.properties[schema.registry.url]}")
    private String schemaRegistryUrl;

    @Autowired
    public void seatProcessorPipeline(StreamsBuilder builder) {

        Map<String, String> serdeConfig = Map.of("schema.registry.url", schemaRegistryUrl);

        SpecificAvroSerde<BookingCommand> commandSerde = newAvroSerde(serdeConfig);
        SpecificAvroSerde<BookingCompletedEvent> completedSerde = newAvroSerde(serdeConfig);
        SpecificAvroSerde<SectionInitCommand> sectionInitSerde = newAvroSerde(serdeConfig);
        SpecificAvroSerde<SectionSeatState> seatStateSerde = newAvroSerde(serdeConfig);
        SpecificAvroSerde<SectionStatusEvent> statusEventSerde = newAvroSerde(serdeConfig);

        // State store for seat inventory
        StoreBuilder<KeyValueStore<String, SectionSeatState>> seatStoreBuilder =
                Stores.keyValueStoreBuilder(
                        Stores.persistentKeyValueStore(StateStore.SEAT_INVENTORY),
                        Serdes.String(),
                        seatStateSerde
                );
        builder.addStateStore(seatStoreBuilder);

        // --- Init path: section-init -> SectionInitProcessor -> section-status ---
        builder.stream(Topic.SECTION_INIT, Consumed.with(Serdes.String(), sectionInitSerde))
                .process(SectionInitProcessor::new, StateStore.SEAT_INVENTORY)
                .mapValues((ValueMapper<SectionSeatState, SectionStatusEvent>) state ->
                        SectionStatusEvent.newBuilder()
                                .setEventId(state.getEventId())
                                .setSection(state.getSection())
                                .setAvailableCount(state.getAvailableCount())
                                .setTimestamp(System.currentTimeMillis())
                                .build())
                .to(Topic.SECTION_STATUS, Produced.with(Serdes.String(), statusEventSerde));

        // --- Allocation path: seat-allocation-requests -> SeatAllocationProcessor -> seat-allocation-results ---
        var completedStream = builder.stream(Topic.SEAT_ALLOCATION_REQUESTS, Consumed.with(Serdes.String(), commandSerde))
                .process(SeatAllocationProcessor::new, StateStore.SEAT_INVENTORY);

        completedStream.to(Topic.SEAT_ALLOCATION_RESULTS, Produced.with(Serdes.String(), completedSerde));

        // --- Status update path: allocation results -> SectionStatusEmitter -> section-status ---
        completedStream
                .process(SectionStatusEmitter::new, StateStore.SEAT_INVENTORY)
                .mapValues((ValueMapper<SectionSeatState, SectionStatusEvent>) state ->
                        SectionStatusEvent.newBuilder()
                                .setEventId(state.getEventId())
                                .setSection(state.getSection())
                                .setAvailableCount(state.getAvailableCount())
                                .setTimestamp(System.currentTimeMillis())
                                .build())
                .to(Topic.SECTION_STATUS, Produced.with(Serdes.String(), statusEventSerde));

    }

    private static <T extends org.apache.avro.specific.SpecificRecord> SpecificAvroSerde<T> newAvroSerde(
            Map<String, String> serdeConfig) {
        SpecificAvroSerde<T> serde = new SpecificAvroSerde<>();
        serde.configure(serdeConfig, false);
        return serde;
    }
}
