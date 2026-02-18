package com.keer.ticketmaster.config;

import com.keer.ticketmaster.avro.*;
import com.keer.ticketmaster.reservation.stream.ReservationCommandProcessor;
import com.keer.ticketmaster.reservation.stream.ReservationResultProcessor;
import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.Materialized;
import org.apache.kafka.streams.kstream.Produced;
import org.apache.kafka.streams.state.KeyValueStore;
import org.apache.kafka.streams.state.StoreBuilder;
import org.apache.kafka.streams.state.Stores;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.EnableKafkaStreams;

import java.util.Map;

@Configuration
@Profile({"reservation-processor", "default"})
@EnableKafkaStreams
public class ReservationProcessorStreamsConfig {

    @Value("${spring.kafka.streams.properties[schema.registry.url]}")
    private String schemaRegistryUrl;

    @Bean
    public KStream<String, ReservationCompletedEvent> reservationPipeline(StreamsBuilder builder) {

        Map<String, String> serdeConfig = Map.of("schema.registry.url", schemaRegistryUrl);

        SpecificAvroSerde<ReservationState> reservationStateSerde = newAvroSerde(serdeConfig);
        SpecificAvroSerde<ReservationCommand> commandSerde = newAvroSerde(serdeConfig);
        SpecificAvroSerde<ReservationRequestedEvent> requestedSerde = newAvroSerde(serdeConfig);
        SpecificAvroSerde<ReservationResultEvent> resultSerde = newAvroSerde(serdeConfig);
        SpecificAvroSerde<ReservationCompletedEvent> completedSerde = newAvroSerde(serdeConfig);

        StoreBuilder<KeyValueStore<String, ReservationState>> reservationStoreBuilder =
                Stores.keyValueStoreBuilder(
                        Stores.persistentKeyValueStore(KafkaConstants.RESERVATION_STORE),
                        Serdes.String(),
                        reservationStateSerde
                );
        builder.addStateStore(reservationStoreBuilder);

        // Step 2: reservation-commands → ReservationCommandProcessor → reservation-requests
        builder.stream(KafkaConstants.TOPIC_RESERVATION_COMMANDS, Consumed.with(Serdes.String(), commandSerde))
                .process(ReservationCommandProcessor::new, KafkaConstants.RESERVATION_STORE)
                .to(KafkaConstants.TOPIC_RESERVATION_REQUESTS, Produced.with(Serdes.String(), requestedSerde));

        // Step 4: reservation-results → ReservationResultProcessor → reservation-completed
        KStream<String, ReservationCompletedEvent> completedStream =
                builder.stream(KafkaConstants.TOPIC_RESERVATION_RESULTS, Consumed.with(Serdes.String(), resultSerde))
                        .process(ReservationResultProcessor::new, KafkaConstants.RESERVATION_STORE);

        completedStream
                .to(KafkaConstants.TOPIC_RESERVATION_COMPLETED, Produced.with(Serdes.String(), completedSerde));

        // Step 5: reservation-completed → repartition by reservationId → query store
        builder.stream(KafkaConstants.TOPIC_RESERVATION_COMPLETED, Consumed.with(Serdes.String(), completedSerde))
                .selectKey((eventKey, event) -> event.getReservationId())
                .toTable(
                        Materialized.<String, ReservationCompletedEvent, KeyValueStore<org.apache.kafka.common.utils.Bytes, byte[]>>as(KafkaConstants.RESERVATION_QUERY_STORE)
                                .withKeySerde(Serdes.String())
                                .withValueSerde(completedSerde)
                );

        return completedStream;
    }

    private static <T extends org.apache.avro.specific.SpecificRecord> SpecificAvroSerde<T> newAvroSerde(
            Map<String, String> serdeConfig) {
        SpecificAvroSerde<T> serde = new SpecificAvroSerde<>();
        serde.configure(serdeConfig, false);
        return serde;
    }
}
