package com.keer.ticketmaster.streaming.topology;

import com.keer.ticketmaster.avro.BookingCompletedEvent;
import com.keer.ticketmaster.config.StateStore;
import com.keer.ticketmaster.config.Topic;
import com.keer.ticketmaster.request.BookingPendingRequests;
import com.keer.ticketmaster.service.TicketService;
import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.utils.Bytes;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.KTable;
import org.apache.kafka.streams.kstream.Materialized;
import org.apache.kafka.streams.state.KeyValueStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.util.Map;

/**
 * API Query (tm-api) topology.
 *
 * Consumes:
 *   - booking-completed (key=bookingId) → KTable for interactive query + DeferredResult
 *
 * State store: booking-query-store (KTable)
 */
@Configuration
@Profile({"api", "default"})
public class BookingQueryTopology {

    @Value("${spring.kafka.streams.properties[schema.registry.url]}")
    private String schemaRegistryUrl;

    @Autowired
    public void bookingQueryPipeline(
            StreamsBuilder builder,
            BookingPendingRequests pendingRequests,
            TicketService ticketService) {

        Map<String, String> serdeConfig = Map.of("schema.registry.url", schemaRegistryUrl);
        SpecificAvroSerde<BookingCompletedEvent> completedSerde = new SpecificAvroSerde<>();
        completedSerde.configure(serdeConfig, false);

        // --- KTable: booking-completed -> query store + foreach ---
        KTable<String, BookingCompletedEvent> table = builder.stream(
                        Topic.BOOKING_COMPLETED,
                        Consumed.with(Serdes.String(), completedSerde))
                .toTable(
                        Materialized.<String, BookingCompletedEvent, KeyValueStore<Bytes, byte[]>>as(StateStore.BOOKING_QUERY)
                                .withKeySerde(Serdes.String())
                                .withValueSerde(completedSerde)
                );

        table.toStream().foreach((bookingId, event) -> {
            pendingRequests.resolve(event);

            if ("CONFIRMED".equalsIgnoreCase(event.getStatus())) {
                ticketService.evictAvailableTicketsCache(event.getEventId());
            }
        });

    }
}
