package com.keer.ticketmaster.streaming.reservation;

import com.keer.ticketmaster.avro.ReservationCommand;
import com.keer.ticketmaster.avro.ReservationCompletedEvent;
import com.keer.ticketmaster.avro.SectionStatusEvent;
import com.keer.ticketmaster.config.KafkaConstants;
import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.utils.Bytes;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.kstream.*;
import org.apache.kafka.streams.state.KeyValueStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Reservation Processor (tm-reservation) topology.
 *
 * Consumes:
 *   - reservation-commands      (key=reservationId) → pre-filter + re-key
 *   - seat-allocation-results   (key=reservationId) → forward to reservation-completed
 *
 * Produces:
 *   - reservation-completed        (key=reservationId) — REJECTED (pre-filter) or forwarded result
 *   - seat-allocation-requests     (key=eventId-section) — passed-through commands
 *
 * State store: section-status-store (GlobalKTable, read-only)
 */
@Configuration
@Profile({"reservation-processor", "default"})
public class ReservationRouterTopology {

    @Value("${spring.kafka.streams.properties[schema.registry.url]}")
    private String schemaRegistryUrl;

    @Autowired
    public void reservationPipeline(StreamsBuilder builder) {

        Map<String, String> serdeConfig = Map.of("schema.registry.url", schemaRegistryUrl);

        SpecificAvroSerde<ReservationCommand> commandSerde = newAvroSerde(serdeConfig);
        SpecificAvroSerde<ReservationCompletedEvent> completedSerde = newAvroSerde(serdeConfig);
        SpecificAvroSerde<SectionStatusEvent> statusSerde = newAvroSerde(serdeConfig);

        // --- GlobalKTable: section-status for pre-filtering ---
        GlobalKTable<String, SectionStatusEvent> sectionStatus = builder.globalTable(
                KafkaConstants.TOPIC_SECTION_STATUS,
                Consumed.with(Serdes.String(), statusSerde),
                Materialized.<String, SectionStatusEvent, KeyValueStore<Bytes, byte[]>>as(KafkaConstants.SECTION_STATUS_STORE)
                        .withKeySerde(Serdes.String())
                        .withValueSerde(statusSerde)
        );

        // --- Stream reservation-commands (key = reservationId) ---
        KStream<String, ReservationCommand> commands = builder.stream(
                KafkaConstants.TOPIC_RESERVATION_COMMANDS,
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

        // Rejected: build REJECTED event -> reservation-completed
        branches.get("prefilter-rejected")
                .mapValues(CommandWithStatus::toRejectedEvent)
                .to(KafkaConstants.TOPIC_RESERVATION_COMPLETED, Produced.with(Serdes.String(), completedSerde));

        // Accepted: re-key to eventId-section -> seat-allocation-requests
        branches.get("prefilter-accepted")
                .map((key, cws) -> KeyValue.pair(
                        cws.command().getEventId() + "-" + cws.command().getSection(),
                        cws.command()))
                .to(KafkaConstants.TOPIC_SEAT_ALLOCATION_REQUESTS, Produced.with(Serdes.String(), commandSerde));

        // --- Forward seat-allocation-results -> reservation-completed ---
        builder.stream(KafkaConstants.TOPIC_SEAT_ALLOCATION_RESULTS,
                        Consumed.with(Serdes.String(), completedSerde))
                .to(KafkaConstants.TOPIC_RESERVATION_COMPLETED, Produced.with(Serdes.String(), completedSerde));

    }

    private record CommandWithStatus(ReservationCommand command, SectionStatusEvent status) {
        boolean hasEnoughSeats() {
            return status == null || status.getAvailableCount() >= command.getSeatCount();
        }

        ReservationCompletedEvent toRejectedEvent() {
            return ReservationCompletedEvent.newBuilder()
                    .setReservationId(command.getReservationId())
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
