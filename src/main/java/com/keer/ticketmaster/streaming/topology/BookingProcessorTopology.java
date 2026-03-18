package com.keer.ticketmaster.streaming.topology;

import com.keer.ticketmaster.avro.*;
import com.keer.ticketmaster.config.StateStore;
import com.keer.ticketmaster.config.Topic;
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

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Merged Booking Processor topology — combines BookingRouter + SeatProcessor
 * into a single Kafka Streams application to eliminate intermediate topic hops.
 *
 * Data flow:
 *   booking-commands (key=bookingId)
 *     → leftJoin GlobalKTable(section-status) for pre-filter
 *     → rejected: booking-completed (key=bookingId)
 *     → accepted: repartition(key=eventId-section)
 *                → SeatAllocationProcessor (state: seat-inventory-store)
 *                → booking-completed (key=bookingId)
 *                → SectionStatusEmitter → section-status (key=eventId-section)
 *
 *   section-init (key=eventId-section)
 *     → SectionInitProcessor (state: seat-inventory-store)
 *     → section-status (key=eventId-section)
 *
 * Eliminated topics: seat-allocation-requests, seat-allocation-results
 */
@Configuration
@Profile({"booking-processor", "default"})
public class BookingProcessorTopology {

    private static final int MAX_LRU_ENTRIES = 1000;

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

        // --- State store: seat inventory (shared by init + allocation) ---
        StoreBuilder<KeyValueStore<String, SectionSeatState>> seatStoreBuilder =
                Stores.keyValueStoreBuilder(
                        Stores.persistentKeyValueStore(StateStore.SEAT_INVENTORY),
                        Serdes.String(),
                        seatStateSerde
                );
        builder.addStateStore(seatStoreBuilder);

        // --- GlobalKTable: section-status for pre-filtering ---
        GlobalKTable<String, SectionStatusEvent> sectionStatus = builder.globalTable(
                Topic.SECTION_STATUS,
                Consumed.with(Serdes.String(), statusSerde),
                Materialized.<String, SectionStatusEvent>as(
                                Stores.lruMap(StateStore.SECTION_STATUS, MAX_LRU_ENTRIES))
                        .withKeySerde(Serdes.String())
                        .withValueSerde(statusSerde)
        );

        // === Init path: section-init → SectionInitProcessor → section-status ===
        builder.stream(Topic.SECTION_INIT, Consumed.with(Serdes.String(), sectionInitSerde))
                .process(SectionInitProcessor::new, StateStore.SEAT_INVENTORY)
                .mapValues((ValueMapper<SectionSeatState, SectionStatusEvent>) state ->
                        SectionStatusEvent.newBuilder()
                                .setEventId(state.getEventId())
                                .setSection(state.getSection())
                                .setAvailableCount(state.getAvailableCount())
                                .setTimestamp(System.currentTimeMillis())
                                .build())
                .to(Topic.SECTION_STATUS, Produced.with(Serdes.String(), statusSerde));

        // === Booking path: booking-commands → pre-filter → seat allocation → booking-completed ===

        // Stream booking-commands (key = bookingId)
        KStream<String, BookingCommand> commands = builder.stream(
                Topic.BOOKING_COMMANDS,
                Consumed.with(Serdes.String(), commandSerde)
        );

        // Left join with GlobalKTable to check seat availability
        KStream<String, CommandWithStatus> joined = commands.leftJoin(
                sectionStatus,
                (key, cmd) -> cmd.getEventId() + "-" + cmd.getSection(),
                CommandWithStatus::new
        );

        // Split into rejected (not enough seats) and accepted
        var branches = joined.split(Named.as("prefilter"))
                .branch((key, cws) -> !cws.hasEnoughSeats(), Branched.as("-rejected"))
                .defaultBranch(Branched.as("-accepted"));

        // Rejected: build REJECTED event → booking-completed
        branches.get("prefilter-rejected")
                .mapValues(CommandWithStatus::toRejectedEvent)
                .to(Topic.BOOKING_COMPLETED, Produced.with(Serdes.String(), completedSerde));

        // Accepted: re-key to eventId-section → repartition → SeatAllocationProcessor
        var completedStream = branches.get("prefilter-accepted")
                .map((key, cws) -> KeyValue.pair(
                        cws.command().getEventId() + "-" + cws.command().getSection(),
                        cws.command()))
                .repartition(Repartitioned.with(Serdes.String(), commandSerde)
                        .withName("seat-allocation"))
                .process(SeatAllocationProcessor::new, StateStore.SEAT_INVENTORY);

        // Allocation results → booking-completed (key=bookingId, set by processor)
        completedStream.to(Topic.BOOKING_COMPLETED, Produced.with(Serdes.String(), completedSerde));

        // Status update: allocation results → SectionStatusEmitter → section-status
        completedStream
                .process(SectionStatusEmitter::new, StateStore.SEAT_INVENTORY)
                .mapValues((ValueMapper<SectionSeatState, SectionStatusEvent>) state ->
                        SectionStatusEvent.newBuilder()
                                .setEventId(state.getEventId())
                                .setSection(state.getSection())
                                .setAvailableCount(state.getAvailableCount())
                                .setTimestamp(System.currentTimeMillis())
                                .build())
                .to(Topic.SECTION_STATUS, Produced.with(Serdes.String(), statusSerde));
    }

    private record CommandWithStatus(BookingCommand command, SectionStatusEvent status) {
        boolean hasEnoughSeats() {
            return status == null || status.getAvailableCount() >= command.getSeatCount();
        }

        BookingCompletedEvent toRejectedEvent() {
            return BookingCompletedEvent.newBuilder()
                    .setBookingId(command.getBookingId())
                    .setEventId(command.getEventId())
                    .setSection(command.getSection())
                    .setSeatCount(command.getSeatCount())
                    .setUserId(command.getUserId())
                    .setStatus("REJECTED")
                    .setAllocatedSeats(List.of())
                    .setTimestamp(Instant.now().toEpochMilli())
                    .build();
        }
    }

    private static <T extends org.apache.avro.specific.SpecificRecord> SpecificAvroSerde<T> newAvroSerde(
            Map<String, String> serdeConfig) {
        SpecificAvroSerde<T> serde = new SpecificAvroSerde<>();
        serde.configure(serdeConfig, false);
        return serde;
    }
}
