package com.keer.ticketmaster.streaming.topology;

import com.keer.ticketmaster.avro.BookingCompletedEvent;
import com.keer.ticketmaster.avro.SectionStatusEvent;
import com.keer.ticketmaster.config.StateStore;
import com.keer.ticketmaster.config.Topic;
import com.keer.ticketmaster.request.BookingPendingRequests;
import com.keer.ticketmaster.response.BookingResponse;
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
 * API Query topology with Redis integration.
 *
 * 1. booking-completed → KTable for interactive query + Redis booking cache
 * 2. section-status → Redis seat counters (for API pre-filter)
 *
 * All Redis I/O is offloaded to a virtual-thread executor to avoid
 * blocking Kafka Streams threads.
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

        // --- KTable: booking-completed → query store + Redis cache ---
        KTable<String, BookingCompletedEvent> table = builder.stream(
                        Topic.BOOKING_COMPLETED,
                        Consumed.with(Serdes.String(), completedSerde))
                .toTable(
                        Materialized.<String, BookingCompletedEvent, KeyValueStore<Bytes, byte[]>>as(StateStore.BOOKING_QUERY)
                                .withKeySerde(Serdes.String())
                                .withValueSerde(completedSerde)
                );

        table.toStream().foreach((bookingId, event) -> {
            // Resolve long-polling DeferredResult — must stay on stream thread (in-memory only)
            pendingRequests.resolve(event);

            // Offload Redis I/O to virtual thread — prevents blocking the stream thread
            virtualThreadExecutor.execute(() -> {
                try {
                    if ("CONFIRMED".equalsIgnoreCase(event.getStatus())) {
                        ticketService.evictAvailableTicketsCache(event.getEventId());
                    }

                    if ("REJECTED".equalsIgnoreCase(event.getStatus())) {
                        redisService.incrementBack(
                                event.getEventId(), event.getSection(),
                                event.getSubPartition(), event.getSeatCount());
                    }

                    redisService.cacheBookingResult(BookingResponse.fromEvent(event));
                } catch (Exception e) {
                    log.debug("Non-fatal: Redis operation failed for booking {}: {}",
                            bookingId, e.getMessage());
                }
            });
        });

        // --- Stream: section-status → Redis seat counters ---
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
