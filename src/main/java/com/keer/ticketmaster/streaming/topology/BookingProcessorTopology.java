package com.keer.ticketmaster.streaming.topology;

import com.keer.ticketmaster.avro.*;
import com.keer.ticketmaster.config.StateStore;
import com.keer.ticketmaster.config.StoreKeyUtil;
import com.keer.ticketmaster.config.Topic;
import com.keer.ticketmaster.streaming.SectionStatusMapper;
import com.keer.ticketmaster.streaming.processor.SeatAllocationProcessor;
import com.keer.ticketmaster.streaming.processor.SectionInitProcessor;
import com.keer.ticketmaster.streaming.processor.SectionStatusEmitter;
import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.kstream.*;
import org.apache.kafka.streams.state.KeyValueStore;
import org.apache.kafka.streams.state.StoreBuilder;
import org.apache.kafka.streams.state.Stores;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.util.Map;

/**
 * Simplified Booking Processor topology — no GlobalKTable pre-filter.
 * Pre-filtering is now done at the API layer via Redis atomic counters.
 *
 * Data flow:
 *   booking-commands (key=bookingId)
 *     → re-key to eventId-section-subPartition (from command.targetSubPartition)
 *     → repartition
 *     → SeatAllocationProcessor (bitmap-based state: seat-inventory-store)
 *     → booking-completed (key=bookingId)
 *     → SectionStatusEmitter → section-status (key=eventId-section-subPartition)
 *
 *   section-init (key=eventId-section)
 *     → SectionInitProcessor (creates N sub-partition states with bitmap)
 *     → section-status (key=eventId-section-subPartition)
 */
@Configuration
@Profile({"booking-processor", "default"})
public class BookingProcessorTopology {

    @Value("${spring.kafka.streams.properties[schema.registry.url]}")
    private String schemaRegistryUrl;

    @Autowired
    public void bookingProcessorPipeline(StreamsBuilder builder) {

        Map<String, String> serdeConfig = Map.of("schema.registry.url", schemaRegistryUrl);

        SpecificAvroSerde<BookingCommand> commandSerde = newAvroSerde(serdeConfig);
        SpecificAvroSerde<BookingCompletedEvent> completedSerde = newAvroSerde(serdeConfig);
        SpecificAvroSerde<SectionStatusEvent> statusSerde = newAvroSerde(serdeConfig);
        SpecificAvroSerde<SectionInitCommand> sectionInitSerde = newAvroSerde(serdeConfig);
        SpecificAvroSerde<SectionSeatState> seatStateSerde = newAvroSerde(serdeConfig);

        // --- State store: seat inventory (bitmap-based, shared by init + allocation) ---
        StoreBuilder<KeyValueStore<String, SectionSeatState>> seatStoreBuilder =
                Stores.keyValueStoreBuilder(
                        Stores.persistentKeyValueStore(StateStore.SEAT_INVENTORY),
                        Serdes.String(),
                        seatStateSerde
                );
        builder.addStateStore(seatStoreBuilder);

        // === Init path: section-init → SectionInitProcessor → section-status ===
        // SectionInitProcessor creates N sub-partition states and forwards N records
        builder.stream(Topic.SECTION_INIT, Consumed.with(Serdes.String(), sectionInitSerde))
                .process(SectionInitProcessor::new, StateStore.SEAT_INVENTORY)
                .mapValues(SectionStatusMapper::toStatusEvent)
                .to(Topic.SECTION_STATUS, Produced.with(Serdes.String(), statusSerde));

        // === Booking path: booking-commands → re-key → seat allocation → booking-completed ===

        KStream<String, BookingCommand> commands = builder.stream(
                Topic.BOOKING_COMMANDS,
                Consumed.with(Serdes.String(), commandSerde)
        );

        // Re-key to eventId-section-subPartition for partition-based routing
        var completedStream = commands
                .map((key, cmd) -> KeyValue.pair(
                        StoreKeyUtil.seatKey(cmd.getEventId(), cmd.getSection(), cmd.getTargetSubPartition()),
                        cmd))
                .repartition(Repartitioned.with(Serdes.String(), commandSerde)
                        .withName("seat-allocation"))
                .process(SeatAllocationProcessor::new, StateStore.SEAT_INVENTORY);

        // Allocation results → booking-completed (key=bookingId, set by processor)
        completedStream.to(Topic.BOOKING_COMPLETED, Produced.with(Serdes.String(), completedSerde));

        // Status update: allocation results → SectionStatusEmitter → section-status
        completedStream
                .process(SectionStatusEmitter::new, StateStore.SEAT_INVENTORY)
                .mapValues(SectionStatusMapper::toStatusEvent)
                .to(Topic.SECTION_STATUS, Produced.with(Serdes.String(), statusSerde));
    }

    private static <T extends org.apache.avro.specific.SpecificRecord> SpecificAvroSerde<T> newAvroSerde(
            Map<String, String> serdeConfig) {
        SpecificAvroSerde<T> serde = new SpecificAvroSerde<>();
        serde.configure(serdeConfig, false);
        return serde;
    }
}
