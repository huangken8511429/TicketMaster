package com.keer.ticketmaster.streaming.topology;

import com.keer.ticketmaster.avro.BookingCommand;
import com.keer.ticketmaster.avro.BookingCompletedEvent;
import com.keer.ticketmaster.config.StoreKeyUtil;
import com.keer.ticketmaster.config.Topic;
import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.kstream.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.util.Map;

/**
 * Booking Router (tm-reservation) topology — simplified.
 * Pre-filtering is now done at the API layer via Redis.
 *
 * Consumes:
 *   - booking-commands         (key=bookingId) → re-key to sub-partition
 *   - seat-allocation-results  (key=bookingId) → forward to booking-completed
 *
 * Produces:
 *   - seat-allocation-requests    (key=eventId-section-subPartition)
 *   - booking-completed           (key=bookingId) — forwarded results
 */
@Configuration
@Profile("reservation-processor")
public class BookingRouterTopology {

    @Value("${spring.kafka.streams.properties[schema.registry.url]}")
    private String schemaRegistryUrl;

    @Autowired
    public void bookingPipeline(StreamsBuilder builder) {

        Map<String, String> serdeConfig = Map.of("schema.registry.url", schemaRegistryUrl);

        SpecificAvroSerde<BookingCommand> commandSerde = newAvroSerde(serdeConfig);
        SpecificAvroSerde<BookingCompletedEvent> completedSerde = newAvroSerde(serdeConfig);

        // --- Stream booking-commands → re-key to sub-partition → seat-allocation-requests ---
        builder.stream(Topic.BOOKING_COMMANDS, Consumed.with(Serdes.String(), commandSerde))
                .map((key, cmd) -> KeyValue.pair(
                        StoreKeyUtil.seatKey(cmd.getEventId(), cmd.getSection(), cmd.getTargetSubPartition()),
                        cmd))
                .to(Topic.SEAT_ALLOCATION_REQUESTS, Produced.with(Serdes.String(), commandSerde));

        // --- Forward seat-allocation-results → booking-completed ---
        builder.stream(Topic.SEAT_ALLOCATION_RESULTS,
                        Consumed.with(Serdes.String(), completedSerde))
                .to(Topic.BOOKING_COMPLETED, Produced.with(Serdes.String(), completedSerde));
    }

    private static <T extends org.apache.avro.specific.SpecificRecord> SpecificAvroSerde<T> newAvroSerde(
            Map<String, String> serdeConfig) {
        SpecificAvroSerde<T> serde = new SpecificAvroSerde<>();
        serde.configure(serdeConfig, false);
        return serde;
    }
}
