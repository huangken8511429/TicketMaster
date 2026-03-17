package com.keer.ticketmaster.streaming.topology;

import com.keer.ticketmaster.avro.BookingCommand;
import com.keer.ticketmaster.avro.BookingCompletedEvent;
import com.keer.ticketmaster.avro.SectionStatusEvent;
import com.keer.ticketmaster.config.StateStore;
import com.keer.ticketmaster.config.Topic;
import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.kstream.*;
import org.apache.kafka.streams.state.Stores;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Booking Processor (tm-reservation) topology.
 *
 * Consumes:
 *   - booking-commands         (key=bookingId) → pre-filter + re-key
 *   - seat-allocation-results  (key=bookingId) → forward to booking-completed
 *
 * Produces:
 *   - booking-completed           (key=bookingId) — REJECTED (pre-filter) or forwarded result
 *   - seat-allocation-requests    (key=eventId-section) — passed-through commands
 *
 * State store: section-status-store (GlobalKTable, read-only)
 */
@Configuration
@Profile({"reservation-processor", "default"})
public class BookingRouterTopology {

    private static final int MAX_LRU_ENTRIES = 1000;

    @Value("${spring.kafka.streams.properties[schema.registry.url]}")
    private String schemaRegistryUrl;

    @Autowired
    public void bookingPipeline(StreamsBuilder builder) {

        Map<String, String> serdeConfig = Map.of("schema.registry.url", schemaRegistryUrl);

        SpecificAvroSerde<BookingCommand> commandSerde = newAvroSerde(serdeConfig);
        SpecificAvroSerde<BookingCompletedEvent> completedSerde = newAvroSerde(serdeConfig);
        SpecificAvroSerde<SectionStatusEvent> statusSerde = newAvroSerde(serdeConfig);

        // --- GlobalKTable: section-status for pre-filtering ---
        GlobalKTable<String, SectionStatusEvent> sectionStatus = builder.globalTable(
                Topic.SECTION_STATUS,
                Consumed.with(Serdes.String(), statusSerde),
                Materialized.<String, SectionStatusEvent>as(
                                Stores.lruMap(StateStore.SECTION_STATUS, MAX_LRU_ENTRIES)
                        )
                        .withKeySerde(Serdes.String())
                        .withValueSerde(statusSerde)
        );

        // --- Stream booking-commands (key = bookingId) ---
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

        // Split into rejected (not enough seats) and accepted (pass to seat processor)
        var branches = joined.split(Named.as("prefilter"))
                .branch((key, cws) -> !cws.hasEnoughSeats(), Branched.as("-rejected"))
                .defaultBranch(Branched.as("-accepted"));

        // Rejected: build REJECTED event -> booking-completed
        branches.get("prefilter-rejected")
                .mapValues(CommandWithStatus::toRejectedEvent)
                .to(Topic.BOOKING_COMPLETED, Produced.with(Serdes.String(), completedSerde));

        // Accepted: re-key to eventId-section -> seat-allocation-requests
        branches.get("prefilter-accepted")
                .map((key, cws) -> KeyValue.pair(
                        cws.command().getEventId() + "-" + cws.command().getSection(),
                        cws.command()))
                .to(Topic.SEAT_ALLOCATION_REQUESTS, Produced.with(Serdes.String(), commandSerde));

        // --- Forward seat-allocation-results -> booking-completed ---
        builder.stream(Topic.SEAT_ALLOCATION_RESULTS,
                        Consumed.with(Serdes.String(), completedSerde))
                .to(Topic.BOOKING_COMPLETED, Produced.with(Serdes.String(), completedSerde));

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
