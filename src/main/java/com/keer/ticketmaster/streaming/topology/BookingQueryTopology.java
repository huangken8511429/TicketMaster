package com.keer.ticketmaster.streaming.topology;

import com.keer.ticketmaster.avro.BookingCompletedEvent;
import com.keer.ticketmaster.avro.SectionStatusEvent;
import com.keer.ticketmaster.config.StateStore;
import com.keer.ticketmaster.config.Topic;
import com.keer.ticketmaster.request.BookingPendingRequests;
import com.keer.ticketmaster.service.SeatAvailabilityRedisService;
import com.keer.ticketmaster.service.TicketService;
import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerde;
import lombok.extern.slf4j.Slf4j;
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
import java.util.concurrent.ExecutorService;

/**
 * API role query topology — booking results stay in the KTable (RocksDB),
 * Redis only mirrors section-status for the frontend availability cards.
 *
 * 1. booking-completed → KTable (Interactive Query)
 *    → foreach resumes pending DeferredResult (ticket-master Service.java:286-307)
 *    → invalidate Spring cache for available ticket lists
 * 2. section-status → Redis seat counters (kept for SectionAvailabilityService /
 *    frontend MVP only — not part of the booking hot path)
 */
@Configuration
@Profile({"api", "default"})
@Slf4j
public class BookingQueryTopology {

    @Value("${spring.kafka.streams.properties[schema.registry.url]}")
    private String schemaRegistryUrl;

    @Autowired
    public void bookingQueryPipeline(
            StreamsBuilder builder,
            BookingPendingRequests pendingRequests,
            TicketService ticketService,
            SeatAvailabilityRedisService redisService,
            ExecutorService virtualThreadExecutor) {

        Map<String, String> serdeConfig = Map.of("schema.registry.url", schemaRegistryUrl);
        SpecificAvroSerde<BookingCompletedEvent> completedSerde = new SpecificAvroSerde<>();
        completedSerde.configure(serdeConfig, false);
        SpecificAvroSerde<SectionStatusEvent> statusSerde = new SpecificAvroSerde<>();
        statusSerde.configure(serdeConfig, false);

        // --- KTable: booking-completed → query store + DeferredResult wake-up ---
        KTable<String, BookingCompletedEvent> table = builder.stream(
                        Topic.BOOKING_COMPLETED,
                        Consumed.with(Serdes.String(), completedSerde))
                .toTable(
                        Materialized.<String, BookingCompletedEvent, KeyValueStore<Bytes, byte[]>>as(StateStore.BOOKING_QUERY)
                                .withKeySerde(Serdes.String())
                                .withValueSerde(completedSerde)
                );

        table.toStream().foreach((bookingId, event) -> {
            // Stay on the stream thread — pendingRequests.resolve is pure in-memory.
            pendingRequests.resolve(event);

            // Off-thread side effects (Spring cache eviction); keep the stream thread hot.
            virtualThreadExecutor.execute(() -> {
                try {
                    if ("CONFIRMED".equalsIgnoreCase(event.getStatus())) {
                        ticketService.evictAvailableTicketsCache(event.getEventId());
                    }
                } catch (Exception e) {
                    log.debug("Non-fatal: cache eviction failed for booking {}: {}",
                            bookingId, e.getMessage());
                }
            });
        });

        // --- Stream: section-status → Redis seat counters (frontend availability only) ---
        builder.stream(Topic.SECTION_STATUS, Consumed.with(Serdes.String(), statusSerde))
                .foreach((key, event) -> {
                    virtualThreadExecutor.execute(() -> {
                        try {
                            redisService.setAvailableCount(
                                    event.getEventId(), event.getSection(),
                                    event.getSubPartition(), event.getAvailableCount());
                            redisService.setSubPartitionCount(
                                    event.getEventId(), event.getSection(),
                                    event.getTotalSubPartitions());
                        } catch (Exception e) {
                            log.debug("Non-fatal: Redis counter sync failed for {}: {}",
                                    key, e.getMessage());
                        }
                    });
                });
    }

}
