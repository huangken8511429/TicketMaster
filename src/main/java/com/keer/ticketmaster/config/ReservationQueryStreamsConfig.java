package com.keer.ticketmaster.config;

import com.keer.ticketmaster.avro.ReservationCompletedEvent;
import com.keer.ticketmaster.avro.SectionStatusEvent;
import com.keer.ticketmaster.reservation.service.ReservationPendingRequests;
import com.keer.ticketmaster.ticket.service.TicketService;
import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.KTable;
import org.apache.kafka.streams.kstream.Materialized;
import org.apache.kafka.streams.state.KeyValueStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.EnableKafkaStreams;

import java.util.Map;

@Configuration
@Profile({"api", "default"})
@EnableKafkaStreams
public class ReservationQueryStreamsConfig {

    @Value("${spring.kafka.streams.properties[schema.registry.url]}")
    private String schemaRegistryUrl;

    @Bean
    public KTable<String, ReservationCompletedEvent> reservationQueryPipeline(
            StreamsBuilder builder,
            ReservationPendingRequests pendingRequests,
            TicketService ticketService) {

        Map<String, String> serdeConfig = Map.of("schema.registry.url", schemaRegistryUrl);
        SpecificAvroSerde<ReservationCompletedEvent> completedSerde = new SpecificAvroSerde<>();
        completedSerde.configure(serdeConfig, false);

        SpecificAvroSerde<SectionStatusEvent> statusSerde = new SpecificAvroSerde<>();
        statusSerde.configure(serdeConfig, false);

        // --- GlobalKTable: section-status → every API Pod has full copy ---
        builder.globalTable(
                KafkaConstants.TOPIC_SECTION_STATUS,
                Consumed.with(Serdes.String(), statusSerde),
                Materialized.<String, SectionStatusEvent, KeyValueStore<org.apache.kafka.common.utils.Bytes, byte[]>>as(KafkaConstants.SECTION_STATUS_STORE)
                        .withKeySerde(Serdes.String())
                        .withValueSerde(statusSerde)
        );

        // --- KTable: reservation-completed → query store + foreach ---
        KTable<String, ReservationCompletedEvent> table = builder.stream(
                        KafkaConstants.TOPIC_RESERVATION_COMPLETED,
                        Consumed.with(Serdes.String(), completedSerde))
                .toTable(
                        Materialized.<String, ReservationCompletedEvent, KeyValueStore<org.apache.kafka.common.utils.Bytes, byte[]>>as(KafkaConstants.RESERVATION_QUERY_STORE)
                                .withKeySerde(Serdes.String())
                                .withValueSerde(completedSerde)
                );

        table.toStream().foreach((reservationId, event) -> {
            pendingRequests.resolve(event);

            if ("CONFIRMED".equalsIgnoreCase(event.getStatus())) {
                ticketService.evictAvailableTicketsCache(event.getEventId());
            }
        });

        return table;
    }
}
