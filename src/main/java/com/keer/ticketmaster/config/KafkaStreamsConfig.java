package com.keer.ticketmaster.config;

import com.keer.ticketmaster.avro.*;
import com.keer.ticketmaster.reservation.service.ReservationPendingRequests;
import com.keer.ticketmaster.reservation.stream.ReservationCommandProcessor;
import com.keer.ticketmaster.reservation.stream.ReservationResultProcessor;
import com.keer.ticketmaster.ticket.stream.SeatAllocationProcessor;
import com.keer.ticketmaster.ticket.stream.SeatEventMaterializeProcessor;
import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.Produced;
import org.apache.kafka.streams.state.KeyValueStore;
import org.apache.kafka.streams.state.StoreBuilder;
import org.apache.kafka.streams.state.Stores;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafkaStreams;
import org.springframework.kafka.config.TopicBuilder;

import java.util.Map;

@Configuration
@EnableKafkaStreams
public class KafkaStreamsConfig {

    public static final String RESERVATION_STORE = "reservation-store";
    public static final String SEAT_INVENTORY_STORE = "seat-inventory-store";

    public static final String TOPIC_RESERVATION_COMMANDS = "reservation-commands";
    public static final String TOPIC_RESERVATION_REQUESTS = "reservation-requests";
    public static final String TOPIC_RESERVATION_RESULTS = "reservation-results";
    public static final String TOPIC_RESERVATION_COMPLETED = "reservation-completed";
    public static final String TOPIC_SEAT_EVENTS = "seat-events";

    @Value("${spring.kafka.streams.properties[schema.registry.url]}")
    private String schemaRegistryUrl;

    @Bean
    public NewTopic seatEventsTopic() {
        return TopicBuilder.name(TOPIC_SEAT_EVENTS).partitions(20).replicas(1).build();
    }

    @Bean
    public NewTopic reservationCommandsTopic() {
        return TopicBuilder.name(TOPIC_RESERVATION_COMMANDS).partitions(20).replicas(1).build();
    }

    @Bean
    public NewTopic reservationRequestsTopic() {
        return TopicBuilder.name(TOPIC_RESERVATION_REQUESTS).partitions(20).replicas(1).build();
    }

    @Bean
    public NewTopic reservationResultsTopic() {
        return TopicBuilder.name(TOPIC_RESERVATION_RESULTS).partitions(20).replicas(1).build();
    }

    @Bean
    public NewTopic reservationCompletedTopic() {
        return TopicBuilder.name(TOPIC_RESERVATION_COMPLETED).partitions(20).replicas(1).build();
    }

    @Bean
    public KStream<String, ReservationCompletedEvent> reservationPipeline(
            StreamsBuilder builder, ReservationPendingRequests pendingRequests) {

        Map<String, String> serdeConfig = Map.of("schema.registry.url", schemaRegistryUrl);

        SpecificAvroSerde<SeatEvent> seatEventSerde = newAvroSerde(serdeConfig);
        SpecificAvroSerde<SeatState> seatStateSerde = newAvroSerde(serdeConfig);
        SpecificAvroSerde<ReservationState> reservationStateSerde = newAvroSerde(serdeConfig);
        SpecificAvroSerde<ReservationCommand> commandSerde = newAvroSerde(serdeConfig);
        SpecificAvroSerde<ReservationRequestedEvent> requestedSerde = newAvroSerde(serdeConfig);
        SpecificAvroSerde<ReservationResultEvent> resultSerde = newAvroSerde(serdeConfig);
        SpecificAvroSerde<ReservationCompletedEvent> completedSerde = newAvroSerde(serdeConfig);

        StoreBuilder<KeyValueStore<String, ReservationState>> reservationStoreBuilder =
                Stores.keyValueStoreBuilder(
                        Stores.persistentKeyValueStore(RESERVATION_STORE),
                        Serdes.String(),
                        reservationStateSerde
                );
        builder.addStateStore(reservationStoreBuilder);

        StoreBuilder<KeyValueStore<String, SeatState>> seatStoreBuilder =
                Stores.keyValueStoreBuilder(
                        Stores.persistentKeyValueStore(SEAT_INVENTORY_STORE),
                        Serdes.String(),
                        seatStateSerde
                );
        builder.addStateStore(seatStoreBuilder);

        // 1. seat-events → materialize to seat-inventory-store
        builder.stream(TOPIC_SEAT_EVENTS, Consumed.with(Serdes.String(), seatEventSerde))
                .process(SeatEventMaterializeProcessor::new, SEAT_INVENTORY_STORE);

        // 2. reservation-commands → ReservationCommandProcessor → reservation-requests
        builder.stream(TOPIC_RESERVATION_COMMANDS, Consumed.with(Serdes.String(), commandSerde))
                .process(ReservationCommandProcessor::new, RESERVATION_STORE)
                .to(TOPIC_RESERVATION_REQUESTS, Produced.with(Serdes.String(), requestedSerde));

        // 3. reservation-requests → SeatAllocationProcessor → reservation-results
        builder.stream(TOPIC_RESERVATION_REQUESTS, Consumed.with(Serdes.String(), requestedSerde))
                .process(SeatAllocationProcessor::new, SEAT_INVENTORY_STORE)
                .to(TOPIC_RESERVATION_RESULTS, Produced.with(Serdes.String(), resultSerde));

        // 4. reservation-results → ReservationResultProcessor → reservation-completed
        KStream<String, ReservationCompletedEvent> completedStream =
                builder.stream(TOPIC_RESERVATION_RESULTS, Consumed.with(Serdes.String(), resultSerde))
                        .process(ReservationResultProcessor::new, RESERVATION_STORE);

        completedStream
                .peek((key, event) -> pendingRequests.resolve(event))
                .to(TOPIC_RESERVATION_COMPLETED, Produced.with(Serdes.String(), completedSerde));

        return completedStream;
    }

    private static <T extends org.apache.avro.specific.SpecificRecord> SpecificAvroSerde<T> newAvroSerde(
            Map<String, String> serdeConfig) {
        SpecificAvroSerde<T> serde = new SpecificAvroSerde<>();
        serde.configure(serdeConfig, false);
        return serde;
    }
}
