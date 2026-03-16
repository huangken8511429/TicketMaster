package com.keer.ticketmaster.streaming.query;

import com.keer.ticketmaster.avro.ReservationCompletedEvent;
import com.keer.ticketmaster.config.KafkaConstants;
import com.keer.ticketmaster.reservation.service.ReservationPendingRequests;
import com.keer.ticketmaster.ticket.service.TicketService;
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
 *   - reservation-completed (key=reservationId) → KTable for interactive query + DeferredResult
 *
 * State store: reservation-query-store (KTable)
 */
@Configuration
@Profile({"api", "default"})
public class ReservationQueryTopology {

    @Value("${spring.kafka.streams.properties[schema.registry.url]}")
    private String schemaRegistryUrl;

    @Autowired
    public void reservationQueryPipeline(
            StreamsBuilder builder,
            ReservationPendingRequests pendingRequests,
            TicketService ticketService) {

        Map<String, String> serdeConfig = Map.of("schema.registry.url", schemaRegistryUrl);
        SpecificAvroSerde<ReservationCompletedEvent> completedSerde = new SpecificAvroSerde<>();
        completedSerde.configure(serdeConfig, false);

        // --- KTable: reservation-completed -> query store + foreach ---
        KTable<String, ReservationCompletedEvent> table = builder.stream(
                        KafkaConstants.TOPIC_RESERVATION_COMPLETED,
                        Consumed.with(Serdes.String(), completedSerde))
                .toTable(
                        Materialized.<String, ReservationCompletedEvent, KeyValueStore<Bytes, byte[]>>as(KafkaConstants.RESERVATION_QUERY_STORE)
                                .withKeySerde(Serdes.String())
                                .withValueSerde(completedSerde)
                );

        table.toStream().foreach((reservationId, event) -> {
            pendingRequests.resolve(event);

            if ("CONFIRMED".equalsIgnoreCase(event.getStatus())) {
                ticketService.evictAvailableTicketsCache(event.getEventId());
            }
        });

    }
}
